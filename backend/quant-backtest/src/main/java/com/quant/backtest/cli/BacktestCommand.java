package com.quant.backtest.cli;

import com.quant.backtest.engine.BacktestRunner;
import com.quant.backtest.engine.BenchmarkService;
import com.quant.backtest.engine.MetricsCalculator;
import com.quant.backtest.model.BacktestResult;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.paper.engine.PaperTrader;
import com.quant.paper.model.PaperTrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 回测 CLI：一键回放 + 指标计算 + HTML 报告。
 *
 * 用法：
 *   java -jar quant-backtest-1.0.0.jar --db ~/workspace/quant/data/quant.db \
 *       --from 2024-01-01 --to 2024-12-31 --strategy-config-version 1 \
 *       --out report-2024.html [--ai-mock] [--initial-capital 100000] [--synthetic]
 *
 * 说明：
 * - 回放在源库的临时拷贝上执行，不污染线上 paper_* 模拟账户；
 * - 回放前锁定指定策略版本，全区间同一版本；
 * - --synthetic 仅用于合成数据验证，报告会明确标注；
 * - 输出不构成投资建议。
 */
public class BacktestCommand {
    public static void main(String[] args) throws Exception {
        Map<String, String> arg = parse(args);
        String dbPath = arg.get("db");
        String fromS = arg.get("from");
        String toS = arg.get("to");
        String verS = arg.get("strategy-config-version");
        String out = arg.get("out");
        if (dbPath == null || fromS == null || toS == null || verS == null || out == null) {
            System.err.println("缺少必要参数");
            printUsage();
            System.exit(2);
            return;
        }
        LocalDate from = LocalDate.parse(fromS);
        LocalDate to = LocalDate.parse(toS);
        int strategyVersion = Integer.parseInt(verS);
        boolean synthetic = arg.containsKey("synthetic");

        Path tempDb = Files.createTempFile("quant-backtest-", ".db");
        try {
            Files.copy(Path.of(dbPath), tempDb, StandardCopyOption.REPLACE_EXISTING);
            try (QuantDb qdb = new QuantDb(tempDb.toString())) {
                ConfigCenter cc = new ConfigCenter(qdb);
                Map<String, String> pinned = cc.loadVersionSnapshot(strategyVersion);
                if (pinned.isEmpty()) {
                    throw new IllegalArgumentException("策略版本不存在: v" + strategyVersion);
                }
                double initialCapital = arg.containsKey("initial-capital")
                        ? Double.parseDouble(arg.get("initial-capital"))
                        : Double.parseDouble(pinned.getOrDefault(
                                StrategyConfig.PAPER_INITIAL_CAPITAL, "100000"));

                AISignalStrategy ai = arg.containsKey("ai-mock") ? new MockAISignalStrategy() : null;
                BacktestRunner runner = new BacktestRunner();
                BacktestRunner.ReplayOutcome outcome =
                        runner.replay(qdb, from, to, strategyVersion, initialCapital, ai);

                BacktestResult result = buildResult(qdb, outcome, from, to,
                        strategyVersion, initialCapital, synthetic, pinned);
                List<PaperTrade> trades = MetricsCalculator.loadTrades(qdb, from, to);

                String html = new com.quant.backtest.report.HtmlReportGenerator()
                        .generate(result, trades);
                Files.writeString(Path.of(out), html);
                System.out.println("回测报告已生成: " + out);
                printConsoleSummary(result);
            }
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    private static BacktestResult buildResult(QuantDb qdb,
                                              BacktestRunner.ReplayOutcome outcome,
                                              LocalDate from, LocalDate to,
                                              int strategyVersion, double initialCapital,
                                              boolean synthetic,
                                              Map<String, String> pinned) throws Exception {
        MetricsCalculator.NavSeries nav = MetricsCalculator.loadNavSeries(qdb, from, to);
        List<LocalDate> dates = nav.dates();
        List<Double> navs = nav.navs();
        List<PaperTrade> trades = MetricsCalculator.loadTrades(qdb, from, to);
        List<PaperTrade> sells = trades.stream()
                .filter(t -> "SELL".equals(t.side())).collect(Collectors.toList());
        List<PaperTrade> buys = trades.stream()
                .filter(t -> "BUY".equals(t.side())).collect(Collectors.toList());

        double lastNav = navs.isEmpty() ? initialCapital : navs.get(navs.size() - 1);
        double totalReturn = MetricsCalculator.totalReturnPct(initialCapital, lastNav);
        double annualized = MetricsCalculator.annualizedPct(totalReturn, navs.size());
        MetricsCalculator.Drawdown dd = MetricsCalculator.maxDrawdown(navs, dates);
        double sharpe = MetricsCalculator.sharpe(navs);
        MetricsCalculator.WinStats ws = MetricsCalculator.winStats(sells);
        double avgHold = MetricsCalculator.avgHoldingDays(trades);
        Map<Integer, List<Double>> holding =
                MetricsCalculator.holdingPeriodReturns(qdb, buys, new int[]{5, 10, 20});

        // 基准
        BenchmarkService.BenchmarkCurve bench =
                new BenchmarkService().build(qdb, dates, initialCapital);
        double excess = (!Double.isNaN(totalReturn) && bench.available())
                ? totalReturn - bench.totalReturnPct() : Double.NaN;

        // 样本内/外 8:2
        int[] split = MetricsCalculator.inOutSplit(navs.size());
        BacktestResult.SliceMetrics inS = slice(dates, navs, sells, 0, split[0], initialCapital);
        BacktestResult.SliceMetrics outS = slice(dates, navs, sells, split[0], navs.size(), initialCapital);

        // 风控统计
        int haltDays = 0;
        List<String> haltReasons = new ArrayList<>();
        for (PaperTrader.DaySummary d : outcome.days()) {
            if (!d.skipped() && !"正常".equals(d.riskStatus())) {
                haltDays++;
                if (!haltReasons.contains(d.riskStatus())) {
                    haltReasons.add(d.riskStatus());
                }
            }
        }
        int cancelled = MetricsCalculator.loadCancelledOrderCount(qdb);

        List<String> assumptions = buildAssumptions(pinned, strategyVersion,
                outcome.pinnedVersionNo(), from, to, synthetic);

        return new BacktestResult(from, to, outcome.pinnedVersionNo(), initialCapital,
                dates, navs,
                bench.available(), bench.values(), bench.totalReturnPct(), excess,
                totalReturn, annualized, dd.pct(), dd.start(), dd.end(), sharpe,
                ws.winRatePct(), ws.profitLossRatio(),
                trades.size(), ws.counted(), avgHold,
                holding, inS, outS,
                haltDays, haltReasons, cancelled,
                assumptions, synthetic);
    }

    private static BacktestResult.SliceMetrics slice(List<LocalDate> dates, List<Double> navs,
                                                     List<PaperTrade> sells,
                                                     int fromIdx, int toIdx, double initialCapital) {
        int n = toIdx - fromIdx;
        if (n <= 0) {
            return new BacktestResult.SliceMetrics(0, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN);
        }
        List<LocalDate> d = dates.subList(fromIdx, toIdx);
        List<Double> v = navs.subList(fromIdx, toIdx);
        double base = v.get(0);
        double tr = MetricsCalculator.totalReturnPct(base, v.get(v.size() - 1));
        double ann = MetricsCalculator.annualizedPct(tr, n);
        MetricsCalculator.Drawdown dd = MetricsCalculator.maxDrawdown(v, d);
        double sh = MetricsCalculator.sharpe(v);
        LocalDate s = d.get(0), e = d.get(d.size() - 1);
        List<PaperTrade> inSlice = sells.stream()
                .filter(t -> !t.tradeDate().isBefore(s) && !t.tradeDate().isAfter(e))
                .collect(Collectors.toList());
        double wr = MetricsCalculator.winStats(inSlice).winRatePct();
        return new BacktestResult.SliceMetrics(n, tr, ann, dd.pct(), sh, wr);
    }

    private static List<String> buildAssumptions(Map<String, String> pinned, int requestedVersion,
                                                 int pinnedVersionNo, LocalDate from, LocalDate to,
                                                 boolean synthetic) {
        StrategyConfig cfg = new StrategyConfig(pinned);
        List<String> a = new ArrayList<>();
        a.add("回测区间 " + from + " ~ " + to + "；策略版本 v" + requestedVersion
                + "（回放前锁定为 v" + pinnedVersionNo + "，全区间同一版本，未做参数寻优）");
        a.add("初始虚拟资金 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_INITIAL_CAPITAL)) + " 元");
        a.add("撮合：信号 T 日收盘产生 → T+1 开盘价 ± 滑点成交；"
                + "涨停买不到 / 跌停卖不出 / 停牌重试 "
                + cfg.getInt(StrategyConfig.PAPER_ORDER_RETRY_DAYS) + " 天后作废；整手（100 股）交易");
        a.add("T+1 交割：买入当日不可卖出");
        a.add("费用（含费用口径）：佣金万 "
                + fmtNum(cfg.getDouble(StrategyConfig.PAPER_COMMISSION_RATE) * 10000)
                + "（单笔 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_COMMISSION_MIN))
                + " 元保底）+ 卖出印花税千分之"
                + fmtNum(cfg.getDouble(StrategyConfig.PAPER_STAMP_TAX_RATE) * 1000)
                + " + 单边滑点 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_SLIPPAGE_PCT)) + "%");
        a.add("选股：M2 三层过滤 + 双通道融合（模式="
                + pinned.getOrDefault(StrategyConfig.FUSION_MODE, "?")
                + "，阈值=" + pinned.getOrDefault(StrategyConfig.FUSION_THRESHOLD, "?") + "）");
        a.add("风控：单日亏损超 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_DAILY_LOSS_HALT_PCT))
                + "% 熔断停开新仓；连续 "
                + cfg.getInt(StrategyConfig.PAPER_CONSECUTIVE_LOSS_HALT_DAYS)
                + " 日亏损暂停新开仓（卖出/止损永远放行）");
        a.add("仓位：单只上限 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_MAX_POSITION_PCT))
                + "%，总仓位上限 " + fmtNum(cfg.getDouble(StrategyConfig.PAPER_MAX_TOTAL_POSITION_PCT))
                + "%；止损 " + fmtNum(cfg.getDouble(StrategyConfig.TRADE_STOP_LOSS_PCT))
                + "% / 止盈 " + fmtNum(cfg.getDouble(StrategyConfig.TRADE_TAKE_PROFIT_PCT)) + "%");
        a.add("数据：M1 日线（前复权价）；基本面按公告日期 PIT 对齐（只用当时已公告数据）");
        a.add("基准：沪深300（000300.SH），按初始资金归一化；缺失交易日顺延上一收盘价");
        a.add("样本划分：按时间 8:2，样本外不参与调参");
        a.add("夏普比率无风险利率取 0；年化按 250 交易日/年");
        if (synthetic) {
            a.add("数据说明：本次为合成数据回放，仅验证回放/指标/报告逻辑，不代表任何真实收益");
        }
        return a;
    }

    private static String fmtNum(double v) {
        return String.format("%.4g", v);
    }

    private static void printConsoleSummary(BacktestResult r) {
        System.out.println("=========== 回测摘要 " + r.from() + " ~ " + r.to() + " ===========");
        System.out.printf("配置版本: v%d | 交易日: %d 天 | 成交: %d 笔%n",
                r.pinnedVersionNo(), r.navDates().size(), r.tradeCount());
        System.out.printf("总收益率: %+.2f%% | 年化: %+.2f%% | 最大回撤: %.2f%% | 夏普: %.2f%n",
                r.totalReturnPct(), r.annualizedPct(), r.maxDrawdownPct(), r.sharpe());
        System.out.printf("胜率: %.1f%% | 盈亏比: %.2f | 平均持仓: %.1f 天%n",
                r.winRatePct(), r.profitLossRatio(), r.avgHoldingDays());
        if (r.benchmarkAvailable()) {
            System.out.printf("沪深300同期: %+.2f%% | 超额收益: %+.2f%%%n",
                    r.benchmarkReturnPct(), r.excessReturnPct());
        } else {
            System.out.println("基准数据缺失，未做对比");
        }
        System.out.printf("样本内: %+.2f%%（%d 天）| 样本外: %+.2f%%（%d 天）%n",
                r.inSample().totalReturnPct(), r.inSample().days(),
                r.outSample().totalReturnPct(), r.outSample().days());
        System.out.printf("风控: 停开新仓 %d 天 | 作废挂单 %d 笔%n", r.haltDays(), r.cancelledOrders());
        if (r.syntheticData()) {
            System.out.println("注意：合成数据回放，仅验证逻辑，不代表任何真实收益。");
        }
        System.out.println("声明：模拟回测，仅供研究，不构成投资建议。");
    }

    private static void printUsage() {
        System.out.println("用法: BacktestCommand --db <库路径> --from YYYY-MM-DD --to YYYY-MM-DD "
                + "--strategy-config-version N --out report.html [--ai-mock] [--initial-capital X] [--synthetic]");
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    m.put(k, args[++i]);
                } else {
                    m.put(k, "true");
                }
            }
        }
        return m;
    }
}

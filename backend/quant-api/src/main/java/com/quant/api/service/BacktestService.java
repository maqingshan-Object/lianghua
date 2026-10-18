package com.quant.api.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.backtest.engine.BacktestRunner;
import com.quant.backtest.engine.BenchmarkService;
import com.quant.backtest.engine.MetricsCalculator;
import com.quant.backtest.model.BacktestResult;
import com.quant.backtest.report.HtmlReportGenerator;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.paper.engine.PaperTrader;
import com.quant.paper.model.PaperTrade;

/**
 * 回测服务：在源库的临时拷贝上回放（不污染线上 paper_* 模拟账户），
 * 生成单文件 HTML 报告并返回指标 JSON。
 *
 * 指标组装逻辑与 M4 BacktestCommand.buildResult 等价（直接复用其公开 API，
 * 不修改 backtest 模块）。
 *
 * 注意：长区间回放为同步执行，可能耗时数分钟（逐日全市场选股），调用方请设足超时。
 */
@Service
public class BacktestService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public BacktestService(ApiProperties props) {
        super(props);
    }

    public Dtos.BacktestResponse run(LocalDate from, LocalDate to, int version) throws Exception {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to 不能早于 from");
        }
        Path reportsDir = Path.of(props.getReportsDir());
        Files.createDirectories(reportsDir);

        Path tempDb = Files.createTempFile("quant-api-backtest-", ".db");
        try {
            Files.copy(Path.of(props.getDbPath()), tempDb, StandardCopyOption.REPLACE_EXISTING);
            BacktestResult result;
            List<PaperTrade> trades;
            try (QuantDb qdb = new QuantDb(tempDb.toString())) {
                ConfigCenter cc = new ConfigCenter(qdb);
                Map<String, String> pinned = cc.loadVersionSnapshot(version);
                if (pinned.isEmpty()) {
                    throw new IllegalArgumentException("策略版本不存在: v" + version);
                }
                double initialCapital = Double.parseDouble(pinned.getOrDefault(
                        StrategyConfig.PAPER_INITIAL_CAPITAL, "100000"));
                AISignalStrategy ai = props.isAiMock() ? new MockAISignalStrategy() : null;

                BacktestRunner runner = new BacktestRunner();
                BacktestRunner.ReplayOutcome outcome =
                        runner.replay(qdb, from, to, version, initialCapital, ai);
                result = buildResult(qdb, outcome, from, to, version, initialCapital, pinned);
                trades = MetricsCalculator.loadTrades(qdb, from, to);
            }

            String fileName = String.format("backtest-%s-%s-v%d-%s.html",
                    from, to, version, LocalDateTime.now().format(TS));
            Path out = reportsDir.resolve(fileName);
            Files.writeString(out, new HtmlReportGenerator().generate(result, trades));
            log.info("回测报告已生成: {}", out);

            return new Dtos.BacktestResponse(
                    toMetricsDto(result),
                    new Dtos.VsBenchmark(result.benchmarkAvailable(),
                            num(result.benchmarkReturnPct()), num(result.excessReturnPct())),
                    "/api/backtest/report/" + fileName,
                    fileName);
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    /** 读取已生成的报告文件（供 /api/backtest/report/{file} 下载）。 */
    public Path reportFile(String fileName) {
        if (!fileName.matches("[A-Za-z0-9._-]+\\.html")) {
            throw new IllegalArgumentException("非法的文件名");
        }
        Path p = Path.of(props.getReportsDir()).resolve(fileName).normalize();
        if (!p.startsWith(Path.of(props.getReportsDir()).normalize()) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("报告不存在: " + fileName);
        }
        return p;
    }

    // ---------- 与 BacktestCommand.buildResult 等价的组装 ----------

    private BacktestResult buildResult(QuantDb qdb, BacktestRunner.ReplayOutcome outcome,
                                       LocalDate from, LocalDate to, int strategyVersion,
                                       double initialCapital, Map<String, String> pinned)
            throws Exception {
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

        BenchmarkService.BenchmarkCurve bench =
                new BenchmarkService().build(qdb, dates, initialCapital);
        double excess = (!Double.isNaN(totalReturn) && bench.available())
                ? totalReturn - bench.totalReturnPct() : Double.NaN;

        int[] split = MetricsCalculator.inOutSplit(navs.size());
        BacktestResult.SliceMetrics inS = slice(dates, navs, sells, 0, split[0]);
        BacktestResult.SliceMetrics outS = slice(dates, navs, sells, split[0], navs.size());

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
                outcome.pinnedVersionNo(), from, to);

        return new BacktestResult(from, to, outcome.pinnedVersionNo(), initialCapital,
                dates, navs,
                bench.available(), bench.values(), bench.totalReturnPct(), excess,
                totalReturn, annualized, dd.pct(), dd.start(), dd.end(), sharpe,
                ws.winRatePct(), ws.profitLossRatio(),
                trades.size(), ws.counted(), avgHold,
                holding, inS, outS,
                haltDays, haltReasons, cancelled,
                assumptions, false);
    }

    private BacktestResult.SliceMetrics slice(List<LocalDate> dates, List<Double> navs,
                                              List<PaperTrade> sells, int fromIdx, int toIdx) {
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

    private List<String> buildAssumptions(Map<String, String> pinned, int requestedVersion,
                                          int pinnedVersionNo, LocalDate from, LocalDate to) {
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
        a.add("数据：M1 日线（前复权价）；基本面按公告日期 PIT 对齐（只用当时已公告数据）");
        a.add("基准：沪深300（000300.SH），按初始资金归一化；缺失交易日顺延上一收盘价");
        a.add("样本划分：按时间 8:2，样本外不参与调参；夏普无风险利率取 0；年化按 250 交易日/年");
        a.add("声明：模拟回测，仅供研究，不构成投资建议；历史不代表未来");
        return a;
    }

    private Dtos.BacktestMetrics toMetricsDto(BacktestResult r) {
        return new Dtos.BacktestMetrics(
                r.from(), r.to(), r.pinnedVersionNo(),
                r.navDates().size(), r.tradeCount(),
                num(r.totalReturnPct()), num(r.annualizedPct()), num(r.maxDrawdownPct()),
                num4(r.sharpe()), num(r.winRatePct()), num(r.profitLossRatio()),
                num(r.avgHoldingDays()),
                toSlice(r.inSample()), toSlice(r.outSample()),
                r.haltDays(), r.cancelledOrders(), r.assumptions());
    }

    private Dtos.SliceDto toSlice(BacktestResult.SliceMetrics s) {
        return new Dtos.SliceDto(s.days(), num(s.totalReturnPct()), num(s.annualizedPct()),
                num(s.maxDrawdownPct()), num4(s.sharpe()), num(s.winRatePct()));
    }

    private static Double num(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : Math.round(v * 100.0) / 100.0;
    }

    private static Double num4(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : Math.round(v * 10000.0) / 10000.0;
    }

    private static String fmtNum(double v) {
        return String.format("%.4g", v);
    }
}

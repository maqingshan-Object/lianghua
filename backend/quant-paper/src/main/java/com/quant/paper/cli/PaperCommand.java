package com.quant.paper.cli;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import com.quant.paper.data.PaperDb;
import com.quant.paper.engine.PaperTrader;
import com.quant.paper.model.PaperPosition;
import com.quant.paper.model.PaperTrade;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟交易 CLI。
 *
 * 用法：
 *   java -jar quant-paper-1.0.0.jar --db ~/workspace/quant/data/quant.db --date 2025-06-30 [--ai-mock]
 *   java -jar quant-paper-1.0.0.jar --db <库> --from 2025-06-01 --to 2025-06-30 [--ai-mock]
 *   java -jar quant-paper-1.0.0.jar --db <库> --reset [--initial-capital 100000]   # 危险：清空模拟账户重来
 *   java -jar quant-paper-1.0.0.jar --db <库> --set fusion.threshold=55 --set trade.max_positions=8 [--effective-date 2025-07-01]
 *       # 调整策略配置：生成新版本，默认下一自然日生效（可用 --effective-date 覆盖，仅用于测试/补跑）
 *
 * 说明：纯模拟，不连接任何券商接口。输出不构成投资建议。
 */
public class PaperCommand {
    public static void main(String[] args) throws Exception {
        Map<String, String> arg = parse(args);
        String dbPath = arg.get("db");
        if (dbPath == null) {
            System.err.println("缺少 --db 参数（SQLite 库路径）");
            printUsage();
            System.exit(2);
        }

        try (QuantDb qdb = new QuantDb(dbPath)) {
            PaperDb paperDb = new PaperDb(qdb);
            ConfigCenter cc = new ConfigCenter(qdb);
            // 注意：不要在这里调 ensureDefaults()（它按"今天"生效，跑历史日期会够不着版本）；
            // PaperTrader.runDay 内的 ensureConfig 会在全新库时按运行日生成 v1。

            if (arg.containsKey("reset")) {
                double capital = arg.containsKey("initial-capital")
                        ? Double.parseDouble(arg.get("initial-capital"))
                        : cc.configForDate(LocalDate.now()).getDouble(StrategyConfig.PAPER_INITIAL_CAPITAL);
                int v = cc.versionIdForDate(LocalDate.now());
                paperDb.resetAll(capital, v);
                System.out.println("模拟账户已重置：初始资金 " + capital + " 元");
                return;
            }

            // --set key=value（可重复）：生成配置新版本，默认下一自然日生效。
            // 纯调参模式（不带日期）只保存版本不跑交易。
            boolean setOnly = arg.containsKey("set")
                    && !arg.containsKey("date") && !arg.containsKey("from");
            if (arg.containsKey("set")) {
                Map<String, String> changes = new HashMap<>();
                for (String kv : arg.get("set").split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("--set 格式应为 key=value，多项用 ; 分隔");
                    }
                    changes.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
                LocalDate eff = arg.containsKey("effective-date")
                        ? LocalDate.parse(arg.get("effective-date"))
                        : LocalDate.now().plusDays(1);
                int v = cc.saveVersion(changes, "CLI 手动调整", eff);
                System.out.println("配置已保存为 v" + v + "，" + eff + " 生效");
                if (setOnly) {
                    return;
                }
            }

            LocalDate from, to;
            if (arg.containsKey("date")) {
                from = to = LocalDate.parse(arg.get("date"));
            } else if (arg.containsKey("from") && arg.containsKey("to")) {
                from = LocalDate.parse(arg.get("from"));
                to = LocalDate.parse(arg.get("to"));
                if (to.isBefore(from)) {
                    throw new IllegalArgumentException("--to 不能早于 --from");
                }
            } else {
                System.err.println("缺少日期参数：--date 或 --from/--to");
                printUsage();
                System.exit(2);
                return;
            }

            MarketDataService mds = new MarketDataService(qdb);
            AISignalStrategy ai = arg.containsKey("ai-mock") ? new MockAISignalStrategy() : null;
            SelectorEngine selector = new SelectorEngine(mds, cc, new StrategyRegistry(), ai);
            PaperTrader trader = new PaperTrader(qdb, paperDb, cc, mds, selector);

            PaperTrader.DaySummary last = null;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                last = trader.runDay(d);
            }

            if (last != null && !last.skipped()) {
                printSummary(paperDb, last);
            } else {
                System.out.println("所选日期无交易日数据，未执行。");
            }
        }
    }

    private static void printSummary(PaperDb paperDb, PaperTrader.DaySummary s) throws Exception {
        System.out.println("=========== 模拟交易日报 " + s.date() + " ===========");
        System.out.printf("配置版本: v%d%n", s.versionNo());
        System.out.printf("账户: 总资产 %.2f | 现金 %.2f | 持仓市值 %.2f%n",
                s.nav(), s.cash(), s.positionValue());
        System.out.printf("累计收益: %+.2f%% | 今日盈亏: %+.2f%n", s.totalReturnPct(), s.dailyPnl());
        System.out.printf("风控: %s%n", s.riskStatus());
        System.out.printf("今日成交: %d 买 / %d 卖 | 新增挂单: %d 买 / %d 卖%n",
                s.filledBuys(), s.filledSells(), s.newBuyOrders(), s.newSellOrders());

        List<PaperTrade> trades = paperDb.loadTradesOn(s.date());
        if (!trades.isEmpty()) {
            System.out.println("--- 今日成交明细 ---");
            for (PaperTrade t : trades) {
                System.out.printf("  %s %s %d股 @%.2f 佣金 %.2f 印花税 %.2f%s 版本 v%s%n",
                        t.side(), t.symbol(), t.qty(), t.price(), t.commission(), t.stampTax(),
                        t.realizedPnl() != null ? String.format(" 实现盈亏 %+.2f", t.realizedPnl()) : "",
                        versionNoOf(paperDb, t.versionId()));
                System.out.println("    理由: " + t.reasonJson());
            }
        }

        List<PaperPosition> positions = paperDb.loadPositions();
        if (!positions.isEmpty()) {
            System.out.println("--- 持仓 ---");
            for (PaperPosition p : positions) {
                System.out.printf("  %s(%s) %d股 成本 %.2f 买入日 %s%n",
                        p.symbol(), p.name(), p.qty(), p.costPrice(), p.buyDate());
            }
        }
        System.out.println("声明：模拟交易，仅供研究，不构成投资建议。");
    }

    private static String versionNoOf(PaperDb paperDb, int versionId) {
        // version_id 即 strategy_config_version.id；展示用 id 足够关联
        return String.valueOf(versionId);
    }

    private static void printUsage() {
        System.out.println("用法: PaperCommand --db <库路径> (--date YYYY-MM-DD | --from A --to B) [--ai-mock]");
        System.out.println("      PaperCommand --db <库路径> --set \"k1=v1;k2=v2\" [--effective-date YYYY-MM-DD]");
        System.out.println("      PaperCommand --db <库路径> --reset [--initial-capital X]");
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

package com.quant.engine.cli;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.scoring.Candidate;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.DeepSeekAISignalStrategy;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 选股 CLI：java -jar quant-engine-1.0.0.jar --date 2026-10-16 --db /path/quant.db [--top 10] [--ai-mock | --ai-real]
 * 输出候选清单到控制台，并持久化到 select_runs / select_candidates（含 version_id）。
 *
 * AI 通道：
 * --ai-mock  用 Mock 固定分联调；
 * --ai-real  用 DeepSeek API 真实打分，Key 从环境变量 DEEPSEEK_API_KEY 读取
 *            （部署机上 export DEEPSEEK_API_KEY=...；Key 绝不进代码/仓库）。
 */
public class SelectCommand {
    private static final Logger log = LoggerFactory.getLogger(SelectCommand.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> argv = parseArgs(args);
        String dateStr = argv.get("--date");
        String dbPath = argv.get("--db");
        if (dateStr == null || dbPath == null) {
            System.err.println("用法: java -jar quant-engine-1.0.0.jar --date YYYY-MM-DD --db /path/quant.db [--top N] [--ai-mock | --ai-real]");
            System.exit(2);
        }
        LocalDate asOf = LocalDate.parse(dateStr);

        try (QuantDb db = new QuantDb(dbPath)) {
            ConfigCenter cc = new ConfigCenter(db);
            cc.ensureDefaults();
            if (cc.versionForDate(asOf) == 0) {
                // 首跑选股日早于 v1 生效日（如历史回放）：补一个生效日为选股日的版本
                cc.saveVersion(Map.of(), "CLI 首跑：版本生效日对齐选股日", asOf);
            }
            StrategyConfig cfg = cc.configForDate(asOf);
            if (argv.containsKey("--top")) {
                // CLI 临时覆盖 top_n（不写回配置表，仅本次运行）
                Map<String, String> m = cfg.asMap();
                m.put(StrategyConfig.SELECT_TOP_N, argv.get("--top"));
                cfg = new StrategyConfig(m);
            }

            MarketDataService mds = new MarketDataService(db);
            StrategyRegistry registry = new StrategyRegistry();
            AISignalStrategy ai = null;
            if (argv.containsKey("--ai-real")) {
                ai = new DeepSeekAISignalStrategy(); // Key 从环境变量 DEEPSEEK_API_KEY 读取
            } else if (argv.containsKey("--ai-mock")) {
                ai = new MockAISignalStrategy();
            }

            SelectorEngine engine = new SelectorEngine(mds, cc, registry, ai);
            SelectorEngine.SelectOutcome outcome = engine.select(asOf);
            long runId = engine.persistRun(asOf, outcome, db, cfg);

            print(outcome, runId, asOf);
        }
    }

    private static void print(SelectorEngine.SelectOutcome outcome, long runId, LocalDate asOf) {
        System.out.println("================ 每日选股 ================");
        System.out.printf("日期：%s  全市场：%d  L1通过：%d  L2通过：%d  配置版本：v%d  run_id=%d%n",
                asOf, outcome.universe(), outcome.passedL1(), outcome.passedL2(),
                outcome.versionNo(), runId);
        System.out.println("------------------------------------------");
        int i = 1;
        for (Candidate c : outcome.candidates()) {
            System.out.printf("%d. %s %s  [%s]  综合 %.1f 分  (AI %s / 引擎 %.1f)%n",
                    i++, c.symbol(), c.name(), c.industry(),
                    c.finalScore(),
                    c.aiScore() == null ? "-" : String.format("%.1f", c.aiScore()),
                    c.engineScore());
            System.out.println("   理由：" + c.reason());
            System.out.printf("   参考买入区间 %.2f-%.2f  止损 %.2f  目标 %.2f%n",
                    c.buyLow(), c.buyHigh(), c.stopLoss(), c.target());
        }
        if (outcome.candidates().isEmpty()) {
            System.out.println("（无入选：可能全市场被过滤，或阈值过高）");
        }
        System.out.println("==========================================");
        System.out.println("注：以上仅为量化筛选参考，不构成投资建议。");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    m.put(args[i], args[i + 1]);
                    i++;
                } else {
                    m.put(args[i], "true");
                }
            }
        }
        return m;
    }
}

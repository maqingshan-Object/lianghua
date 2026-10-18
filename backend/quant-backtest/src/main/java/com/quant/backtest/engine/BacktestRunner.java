package com.quant.backtest.engine;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import com.quant.paper.data.PaperDb;
import com.quant.paper.engine.PaperTrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回测回放器。
 *
 * 设计原则：不重写撮合/费用/风控——直接复用 M3 {@link PaperTrader#runDay}，
 * 在 [from, to] 区间内逐交易日推进。回放前把 --strategy-config-version
 * 指定的版本快照锁定为一个新版本（effective_date=from），保证整个区间
 * 使用同一套参数；回放后校验版本数未变（防并发改配置导致不可复现）。
 *
 * 调用方约定：
 *  - 传入的 QuantDb 必须是回测专用库（本方法会 resetAll 清空所有 paper_* 表）；
 *  - 沙箱无真实行情时，用合成数据验证回放逻辑，真实回测在部署机跑。
 */
public class BacktestRunner {
    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    public record ReplayOutcome(List<PaperTrader.DaySummary> days, int pinnedVersionNo) {
    }

    public ReplayOutcome replay(QuantDb qdb, LocalDate from, LocalDate to,
                                int strategyVersionNo, double initialCapital,
                                AISignalStrategy ai) throws Exception {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("--to 不能早于 --from");
        }
        PaperDb paperDb = new PaperDb(qdb);
        ConfigCenter cc = new ConfigCenter(qdb);

        Map<String, String> snapshot = cc.loadVersionSnapshot(strategyVersionNo);
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("策略版本不存在: v" + strategyVersionNo);
        }
        int pinnedNo = cc.saveVersion(snapshot,
                "回测锁定 v" + strategyVersionNo + " [" + from + "~" + to + "]", from);
        int pinnedId = cc.versionIdForDate(from);
        int maxVersionBefore = cc.maxVersionNo();

        paperDb.resetAll(initialCapital, pinnedId);
        log.info("回测回放 [{} ~ {}]，锁定配置 v{}（快照自 v{}），初始资金 {} 元",
                from, to, pinnedNo, strategyVersionNo, initialCapital);

        MarketDataService mds = new MarketDataService(qdb);
        SelectorEngine selector = new SelectorEngine(mds, cc, new StrategyRegistry(), ai);
        PaperTrader trader = new PaperTrader(qdb, paperDb, cc, mds, selector);

        List<PaperTrader.DaySummary> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(trader.runDay(d));
        }
        if (cc.maxVersionNo() != maxVersionBefore) {
            log.warn("回测期间配置版本发生变化（{} -> {}），本次回放结果可能不可复现",
                    maxVersionBefore, cc.maxVersionNo());
        }
        long tradedDays = days.stream().filter(s -> !s.skipped()).count();
        log.info("回放完成：{} 个自然日，{} 个交易日", days.size(), tradedDays);
        return new ReplayOutcome(days, pinnedNo);
    }
}

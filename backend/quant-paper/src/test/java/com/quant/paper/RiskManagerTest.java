package com.quant.paper;

import com.quant.engine.config.StrategyConfig;
import com.quant.paper.data.PaperDb;
import com.quant.paper.engine.RiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 风控单测：单日熔断 / 连续亏损暂停。 */
class RiskManagerTest {
    private PaperTestDb tdb;
    private RiskManager risk;
    private StrategyConfig cfg;
    private final LocalDate today = LocalDate.of(2025, 6, 30);

    @BeforeEach
    void setUp() throws Exception {
        tdb = new PaperTestDb();
        tdb.initConfigWith(Map.of(), today.minusDays(30));
        cfg = tdb.cc.configForDate(today);
        risk = new RiskManager(tdb.paperDb);
        tdb.paperDb.ensureAccount(100000, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    @Test
    void 单日亏损超3个点停开新仓() throws Exception {
        // 前一日净值 100000，今日 96000 → -4%
        tdb.paperDb.saveDailyNav(today.minusDays(1), 100000, 100000, 0, 0, 1);
        RiskManager.RiskDecision d = risk.evaluate(96000.0, 100000.0, 100000, cfg);
        assertThat(d.haltNewBuys()).isTrue();
        assertThat(d.reason()).contains("单日亏损");
    }

    @Test
    void 单日亏损未达线不拦截() throws Exception {
        tdb.paperDb.saveDailyNav(today.minusDays(1), 100000, 100000, 0, 0, 1);
        RiskManager.RiskDecision d = risk.evaluate(98000.0, 100000.0, 100000, cfg);
        assertThat(d.haltNewBuys()).isFalse();
    }

    @Test
    void 连续5日亏损暂停并告警() throws Exception {
        for (int i = 5; i >= 1; i--) {
            LocalDate d = today.minusDays(i);
            tdb.paperDb.saveDailyNav(d, 100000 - i * 100, 90000, 10000 - i * 100, -100, 1);
        }
        RiskManager.RiskDecision d = risk.evaluate(99000.0, 99500.0, 100000, cfg);
        assertThat(d.haltNewBuys()).isTrue();
        assertThat(d.reason()).contains("连续 5");
    }

    @Test
    void 连续亏损中断则不暂停() throws Exception {
        // 4 连亏 + 1 天盈利 → 不触发
        for (int i = 5; i >= 2; i--) {
            tdb.paperDb.saveDailyNav(today.minusDays(i), 100000 - i * 100, 90000, 0, -100, 1);
        }
        tdb.paperDb.saveDailyNav(today.minusDays(1), 100000, 90000, 0, 500, 1);
        RiskManager.RiskDecision d = risk.evaluate(100000.0, 100000.0, 100000, cfg);
        assertThat(d.haltNewBuys()).isFalse();
    }

    @Test
    void 熔断阈值可配() throws Exception {
        StrategyConfig cfg2 = new StrategyConfig(Map.of(
                StrategyConfig.PAPER_DAILY_LOSS_HALT_PCT, "1",
                StrategyConfig.PAPER_CONSECUTIVE_LOSS_HALT_DAYS, "5"));
        tdb.paperDb.saveDailyNav(today.minusDays(1), 100000, 100000, 0, 0, 1);
        // -2%：默认 3% 不拦，配成 1% 则拦
        assertThat(risk.evaluate(98000.0, 100000.0, 100000, cfg2).haltNewBuys()).isTrue();
    }
}

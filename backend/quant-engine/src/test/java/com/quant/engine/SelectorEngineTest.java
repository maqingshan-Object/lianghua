package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.scoring.Candidate;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端选股测试（合成数据）：
 * AAA 上涨+放量 → 应入选；ST/次新/亏损 → 分别被 L1/L2 拦截。
 */
class SelectorEngineTest {
    private TestDb tdb;
    private final LocalDate asOf = LocalDate.of(2025, 6, 30);

    @BeforeEach
    void setUp() throws Exception {
        tdb = new TestDb();

        // AAA：2020 上市，非 ST，银行，120 根上涨 + 近 5 日 3 倍放量 + 末日跳空 3%（真突破）
        tdb.insertStock("600001.SH", "AAA银行", "银行", LocalDate.of(2020, 1, 1), false, false);
        tdb.insertRisingBars("600001.SH", asOf, 120, 0.05, 100000, 3.0, 0.03);
        tdb.insertFundamental("600001.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29),
                12.0, 10.0, 15.0, 40.0, 5e8);

        // ST 股 → L1 拦截
        tdb.insertStock("600002.SH", "ST某某", "银行", LocalDate.of(2020, 1, 1), true, false);
        tdb.insertRisingBars("600002.SH", asOf, 120, 0.05, 100000, 3.0);

        // 次新（上市 6 个月）→ L1 拦截
        tdb.insertStock("600003.SH", "次新股", "银行", asOf.minusMonths(6), false, false);
        tdb.insertRisingBars("600003.SH", asOf, 120, 0.05, 100000, 3.0);

        // 亏损 → L2 拦截
        tdb.insertStock("600004.SH", "亏损股", "银行", LocalDate.of(2020, 1, 1), false, false);
        tdb.insertRisingBars("600004.SH", asOf, 120, 0.05, 100000, 3.0);
        tdb.insertFundamental("600004.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29),
                -4.0, -6.0, -30.0, 40.0, 1e8);
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    private SelectorEngine engine(Map<String, String> overrides, MockAISignalStrategy ai) throws Exception {
        tdb.initConfigWith(overrides == null ? Map.of() : overrides, asOf);
        MarketDataService mds = new MarketDataService(tdb.db);
        return new SelectorEngine(mds, tdb.cc, new StrategyRegistry(), ai);
    }

    @Test
    void 纯引擎模式_只有AAA入选() throws Exception {
        // 合成数据引擎分约 55.75，阈值设 55（验证入选机制；默认阈值 60 针对真实市场）
        SelectorEngine eng = engine(Map.of(StrategyConfig.FUSION_THRESHOLD, "55"), null);
        SelectorEngine.SelectOutcome out = eng.select(asOf);

        assertThat(out.universe()).isEqualTo(4);
        assertThat(out.passedL1()).isEqualTo(2); // AAA + 亏损股
        assertThat(out.passedL2()).isEqualTo(1); // 只有 AAA
        assertThat(out.candidates()).hasSize(1);
        Candidate c = out.candidates().get(0);
        assertThat(c.symbol()).isEqualTo("600001.SH");
        assertThat(c.finalScore()).isEqualTo(c.engineScore());
        assertThat(c.aiScore()).isNull(); // 纯引擎模式无 AI 分
        assertThat(c.factorScores()).containsKeys("ma_alignment", "breakout", "volume_surge", "pullback");
        assertThat(c.factorScores().get("ma_alignment")).isGreaterThan(0);
        assertThat(c.factorScores().get("breakout")).isGreaterThan(0);
        assertThat(c.factorScores().get("volume_surge")).isGreaterThan(0);
        assertThat(c.stopLoss()).isLessThan(c.buyHigh());
        assertThat(c.target()).isGreaterThan(c.buyHigh());
        assertThat(c.reason()).contains("均线多头排列").contains("趋势突破").contains("放量");
    }

    @Test
    void 融合模式_双分并存() throws Exception {
        Map<String, String> overrides = Map.of(
                StrategyConfig.FUSION_MODE, "FUSION",
                StrategyConfig.FUSION_AI_WEIGHT, "0.5",
                StrategyConfig.FUSION_ENGINE_WEIGHT, "0.5",
                StrategyConfig.FUSION_THRESHOLD, "60");
        SelectorEngine eng = engine(overrides, new MockAISignalStrategy(100.0));
        SelectorEngine.SelectOutcome out = eng.select(asOf);

        assertThat(out.candidates()).hasSize(1);
        Candidate c = out.candidates().get(0);
        assertThat(c.aiScore()).isEqualTo(100.0);
        // final = 100*0.5 + engine*0.5
        assertThat(c.finalScore()).isCloseTo(50 + c.engineScore() * 0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(c.reason()).contains("AI");
    }

    @Test
    void 选股运行持久化_关联版本() throws Exception {
        SelectorEngine eng = engine(Map.of(StrategyConfig.FUSION_THRESHOLD, "55"), null);
        SelectorEngine.SelectOutcome out = eng.select(asOf);
        StrategyConfig cfg = tdb.cc.configForDate(asOf);
        long runId = eng.persistRun(asOf, out, tdb.db, cfg);

        try (Statement st = tdb.db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT version_id, candidates FROM select_runs WHERE id=" + runId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("version_id")).isEqualTo(out.versionId());
            assertThat(rs.getInt("candidates")).isEqualTo(1);
        }
        try (PreparedStatement ps = tdb.db.connection().prepareStatement(
                "SELECT symbol, final_score, ai_score FROM select_candidates WHERE run_id=?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("symbol")).isEqualTo("600001.SH");
                assertThat(rs.getDouble("final_score")).isGreaterThan(0);
            }
        }
    }

    @Test
    void 阈值过高时无人入选() throws Exception {
        SelectorEngine eng = engine(Map.of(StrategyConfig.FUSION_THRESHOLD, "100"), null);
        SelectorEngine.SelectOutcome out = eng.select(asOf);
        assertThat(out.candidates()).isEmpty();
    }
}

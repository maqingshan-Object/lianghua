package com.quant.backtest;

import com.quant.backtest.engine.BacktestRunner;
import com.quant.backtest.engine.BenchmarkService;
import com.quant.backtest.engine.MetricsCalculator;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.StrategyRegistry;
import com.quant.paper.data.PaperDb;
import com.quant.paper.engine.PaperTrader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放器测试（合成数据，仅验证逻辑）：
 * 回放 == 逐日手动跑 / 基准交易日对齐 / 样本内外 8:2 划分。
 */
class BacktestRunnerTest {
    private BacktestTestDb tdb;
    private final LocalDate TO = LocalDate.of(2025, 7, 14);
    private final LocalDate FROM = TO.minusDays(11); // 12 个自然日

    @BeforeEach
    void setUp() throws Exception {
        tdb = new BacktestTestDb();
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    /** AAA：上涨+放量 → 候选股（阈值 55，沿用 M2/M3 测试口径）。 */
    private void insertAAA() throws Exception {
        tdb.insertStock("600001.SH", "AAA银行", "银行", LocalDate.of(2020, 1, 1), false);
        tdb.insertRisingBars("600001.SH", TO, 120, 0.05, 100000, 3.0);
        tdb.insertFundamental("600001.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29),
                12.0, 10.0, 15.0, 40.0, 5e8);
        tdb.initConfigWith(Map.of(StrategyConfig.FUSION_THRESHOLD, "55"), FROM.minusDays(30));
    }

    private List<String> dumpNav(QuantDb qdb) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT trade_date, total_asset, cash, position_value, daily_pnl, version_id " +
                     "FROM paper_daily_nav ORDER BY trade_date")) {
            while (rs.next()) {
                rows.add(String.format("%s|%.2f|%.2f|%.2f|%.2f|%d",
                        rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getInt(6)));
            }
        }
        return rows;
    }

    private List<String> dumpTrades(QuantDb qdb) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT signal_date, trade_date, symbol, side, price, qty, commission, " +
                     "stamp_tax, reason_json, version_id, realized_pnl " +
                     "FROM paper_trades ORDER BY trade_date, id")) {
            while (rs.next()) {
                double pnl = rs.getDouble("realized_pnl");
                String pnlS = rs.wasNull() ? "null" : String.format("%.2f", pnl);
                rows.add(String.format("%s|%s|%s|%s|%.2f|%d|%.2f|%.2f|%s|%d|%s",
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getLong(6), rs.getDouble(7), rs.getDouble(8),
                        rs.getString(9), rs.getInt(10), pnlS));
            }
        }
        return rows;
    }

    @Test
    void 回放与逐日手动跑结果一致() throws Exception {
        insertAAA();
        tdb.closeConnection();

        Path copyA = Files.createTempFile("bt-a-", ".db");
        Path copyB = Files.createTempFile("bt-b-", ".db");
        Files.copy(tdb.path, copyA, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(tdb.path, copyB, StandardCopyOption.REPLACE_EXISTING);

        // A：回放器
        List<String> navA, tradesA;
        try (QuantDb qdbA = new QuantDb(copyA.toString())) {
            new BacktestRunner().replay(qdbA, FROM, TO, 1, 100000, null);
            navA = dumpNav(qdbA);
            tradesA = dumpTrades(qdbA);
        }

        // B：逐日手动跑（同样的版本锁定 + reset + runDay 循环）
        List<String> navB, tradesB;
        try (QuantDb qdbB = new QuantDb(copyB.toString())) {
            PaperDb paperDb = new PaperDb(qdbB);
            ConfigCenter cc = new ConfigCenter(qdbB);
            Map<String, String> snapshot = cc.loadVersionSnapshot(1);
            cc.saveVersion(snapshot, "测试手动锁定", FROM);
            paperDb.resetAll(100000, cc.versionIdForDate(FROM));
            MarketDataService mds = new MarketDataService(qdbB);
            SelectorEngine selector = new SelectorEngine(mds, cc, new StrategyRegistry(), null);
            PaperTrader trader = new PaperTrader(qdbB, paperDb, cc, mds, selector);
            for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
                trader.runDay(d);
            }
            navB = dumpNav(qdbB);
            tradesB = dumpTrades(qdbB);
        }

        assertThat(navA).isNotEmpty();
        assertThat(navA).isEqualTo(navB);
        assertThat(tradesA).isEqualTo(tradesB);

        Files.deleteIfExists(copyA);
        Files.deleteIfExists(copyB);
    }

    @Test
    void 基准按交易日对齐_缺失日期顺延() throws Exception {
        // 000300.SH：5 天中有 1 天缺失（第 3 天）
        tdb.insertStock("000300.SH", "沪深300", "指数", LocalDate.of(2020, 1, 1), false);
        LocalDate d0 = LocalDate.of(2025, 7, 7);
        double[][] rows = new double[5][5];
        for (int i = 0; i < 5; i++) {
            double c = 4000 + i * 10;
            rows[i] = new double[]{c * 0.99, c * 1.01, c * 0.98, c, Double.NaN};
        }
        // 逐天写入，跳过第 3 天（d0+2）
        for (int i : new int[]{0, 1, 3, 4}) {
            tdb.insertBars("000300.SH", d0.plusDays(i), new double[][]{rows[i]});
        }

        List<LocalDate> navDates = List.of(d0, d0.plusDays(1), d0.plusDays(2),
                d0.plusDays(3), d0.plusDays(4));
        BenchmarkService.BenchmarkCurve curve =
                new BenchmarkService().build(tdb.db, navDates, 100000);

        assertThat(curve.available()).isTrue();
        assertThat(curve.values()).hasSize(5);
        // 第 3 天（缺失）顺延第 2 天的收盘价
        assertThat(curve.values().get(2)).isEqualTo(curve.values().get(1));
        // 首日归一化为初始资金
        assertThat(curve.values().get(0)).isEqualTo(100000.0);
    }

    @Test
    void 样本内外按8比2划分() {
        assertThat(MetricsCalculator.inOutSplit(10)).isEqualTo(new int[]{8, 2});
        assertThat(MetricsCalculator.inOutSplit(5)).isEqualTo(new int[]{4, 1});
        assertThat(MetricsCalculator.inOutSplit(0)).isEqualTo(new int[]{0, 0});
    }
}

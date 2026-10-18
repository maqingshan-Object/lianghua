package com.quant.backtest;

import com.quant.backtest.engine.BacktestRunner;
import com.quant.backtest.engine.MetricsCalculator;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;
import com.quant.paper.model.PaperTrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 指标计算测试（合成数据，仅验证逻辑）：
 * 费用口径 / 已知序列的最大回撤与夏普 / 胜率盈亏比 / FIFO 持仓天数。
 */
class MetricsCalculatorTest {
    private BacktestTestDb tdb;
    private final LocalDate TO = LocalDate.of(2025, 7, 14);
    private final LocalDate FROM = TO.minusDays(11);

    @BeforeEach
    void setUp() throws Exception {
        tdb = new BacktestTestDb();
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    private PaperTrade trade(long id, LocalDate signal, LocalDate date, String symbol,
                             String side, double price, long qty, Double pnl) {
        return new PaperTrade(id, signal, date, symbol, side, price, qty,
                5.0, 0.0, "{\"signal_type\":\"TEST\"}", 1, pnl);
    }

    @Test
    void 费用开启时收益严格低于裸收益() throws Exception {
        tdb.insertStock("600001.SH", "AAA银行", "银行", LocalDate.of(2020, 1, 1), false);
        tdb.insertRisingBars("600001.SH", TO, 120, 0.05, 100000, 3.0);
        tdb.insertFundamental("600001.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29),
                12.0, 10.0, 15.0, 40.0, 5e8);
        // 3 根延续 bar：07-14 产生买入信号，T+1 在 07-15 开盘成交（需区间内有行情）
        LocalDate ext = TO.plusDays(3);
        double[][] tail = new double[3][5];
        for (int i = 0; i < 3; i++) {
            double c = 16.0 + i * 0.05;
            tail[i] = new double[]{c * 0.99, c * 1.01, c * 0.98, c, Double.NaN};
        }
        tdb.insertBars("600001.SH", ext, tail);
        // v1：默认费用；v2：费用清零（裸收益，仅测试对比用）
        tdb.initConfigWith(Map.of(StrategyConfig.FUSION_THRESHOLD, "55"), FROM.minusDays(30));
        tdb.cc.saveVersion(Map.of(
                        StrategyConfig.PAPER_COMMISSION_RATE, "0",
                        StrategyConfig.PAPER_COMMISSION_MIN, "0",
                        StrategyConfig.PAPER_STAMP_TAX_RATE, "0",
                        StrategyConfig.PAPER_SLIPPAGE_PCT, "0"),
                "测试：裸费用", FROM.minusDays(30));
        tdb.closeConnection();

        double navWithFees = replayFinalNav(1, FROM, ext);
        double navNoFees = replayFinalNav(2, FROM, ext);

        // 至少一笔买入成交（佣金 5 元保底），费用口径必然严格更低
        assertThat(navWithFees).isLessThan(navNoFees);
    }

    private double replayFinalNav(int versionNo, LocalDate from, LocalDate to) throws Exception {
        Path copy = Files.createTempFile("bt-fee-", ".db");
        Files.copy(tdb.path, copy, StandardCopyOption.REPLACE_EXISTING);
        try (QuantDb qdb = new QuantDb(copy.toString())) {
            new BacktestRunner().replay(qdb, from, to, versionNo, 100000, null);
            MetricsCalculator.NavSeries nav =
                    MetricsCalculator.loadNavSeries(qdb, from, to);
            assertThat(nav.navs()).isNotEmpty();
            return nav.navs().get(nav.navs().size() - 1);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    @Test
    void 最大回撤已知序列() {
        LocalDate d0 = LocalDate.of(2025, 1, 1);
        List<LocalDate> dates = List.of(d0, d0.plusDays(1), d0.plusDays(2), d0.plusDays(3), d0.plusDays(4));
        List<Double> navs = List.of(100.0, 110.0, 105.0, 90.0, 95.0);
        MetricsCalculator.Drawdown dd = MetricsCalculator.maxDrawdown(navs, dates);
        assertThat(dd.pct()).isCloseTo(-18.18, within(0.01));
        assertThat(dd.start()).isEqualTo(d0.plusDays(1));
        assertThat(dd.end()).isEqualTo(d0.plusDays(3));
    }

    @Test
    void 夏普比率_上涨为正_横盘为零_不足两点为NaN() {
        List<Double> rising = List.of(100.0, 101.0, 102.0, 103.0, 104.0, 105.0);
        assertThat(MetricsCalculator.sharpe(rising)).isPositive();
        List<Double> flat = List.of(100.0, 100.0, 100.0, 100.0);
        assertThat(MetricsCalculator.sharpe(flat)).isEqualTo(0.0);
        assertThat(MetricsCalculator.sharpe(List.of(100.0))).isNaN();
    }

    @Test
    void 胜率与盈亏比() {
        LocalDate d = LocalDate.of(2025, 1, 1);
        List<PaperTrade> sells = List.of(
                trade(1, d, d, "A", "SELL", 10, 100, 100.0),
                trade(2, d, d, "B", "SELL", 10, 100, 200.0),
                trade(3, d, d, "C", "SELL", 10, 100, -50.0),
                trade(4, d, d, "D", "SELL", 10, 100, null)); // 未实现的不计入
        MetricsCalculator.WinStats ws = MetricsCalculator.winStats(sells);
        assertThat(ws.counted()).isEqualTo(3);
        assertThat(ws.wins()).isEqualTo(2);
        assertThat(ws.winRatePct()).isCloseTo(66.67, within(0.01));
        assertThat(ws.profitLossRatio()).isCloseTo(3.0, within(0.001)); // 150/50
        // 无卖出时为 NaN（诚实：不编造）
        assertThat(MetricsCalculator.winStats(List.of()).winRatePct()).isNaN();
    }

    @Test
    void 平均持仓天数_FIFO按股数加权() {
        LocalDate d0 = LocalDate.of(2025, 1, 1);
        List<PaperTrade> trades = List.of(
                trade(1, d0, d0, "A", "BUY", 10, 100, null),
                trade(2, d0, d0.plusDays(5), "A", "BUY", 10, 100, null),
                trade(3, d0, d0.plusDays(10), "A", "SELL", 12, 150, 300.0));
        // (100股*10天 + 50股*5天) / 150股 = 8.333天
        assertThat(MetricsCalculator.avgHoldingDays(trades)).isCloseTo(8.333, within(0.001));
        assertThat(MetricsCalculator.avgHoldingDays(List.of())).isNaN();
    }

    @Test
    void 年化收益率_不足一天为NaN() {
        assertThat(MetricsCalculator.annualizedPct(10.0, 0)).isNaN();
        // 250 天翻倍 → 年化 100%
        assertThat(MetricsCalculator.annualizedPct(100.0, 250)).isCloseTo(100.0, within(0.001));
    }
}

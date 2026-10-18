package com.quant.paper;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.select.SelectorEngine;
import com.quant.engine.strategy.StrategyRegistry;
import com.quant.paper.engine.PaperTrader;
import com.quant.paper.model.PaperPosition;
import com.quant.paper.model.PaperTrade;
import com.quant.paper.model.PendingOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模拟交易端到端测试（合成数据）：
 * T+1 交割 / 止损触发 / 每笔交易理由+版本 / 资金不足降级。
 */
class PaperTraderTest {
    private PaperTestDb tdb;
    private final LocalDate D = LocalDate.of(2025, 6, 30);

    @BeforeEach
    void setUp() throws Exception {
        tdb = new PaperTestDb();
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    private PaperTrader trader(Map<String, String> overrides) throws Exception {
        tdb.initConfigWith(overrides, D.minusDays(30));
        MarketDataService mds = new MarketDataService(tdb.db);
        SelectorEngine selector = new SelectorEngine(mds, tdb.cc, new StrategyRegistry(), null);
        return new PaperTrader(tdb.db, tdb.paperDb, tdb.cc, mds, selector);
    }

    /** AAA：上涨+放量+末日跳空 → 候选股（阈值 55，沿用 M2 测试口径）。 */
    private void insertAAA(LocalDate endDate) throws Exception {
        tdb.insertStock("600001.SH", "AAA银行", "银行", LocalDate.of(2020, 1, 1), false);
        tdb.insertRisingBars("600001.SH", endDate, 120, 0.05, 100000, 3.0, 0.03);
        tdb.insertFundamental("600001.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 29),
                12.0, 10.0, 15.0, 40.0, 5e8);
    }

    @Test
    void T加1_买入次日开盘成交且当日不可卖() throws Exception {
        insertAAA(D);
        // D+1 的 bar 专供挂单结算（开盘价成交）
        tdb.insertBars("600001.SH", D.plusDays(1),
                new double[][]{{16.30, 16.60, 16.20, 16.50, Double.NaN}});
        PaperTrader trader = trader(Map.of(StrategyConfig.FUSION_THRESHOLD, "55"));

        // D 日：产生买入挂单（信号日 D）
        PaperTrader.DaySummary s1 = trader.runDay(D);
        assertThat(s1.skipped()).isFalse();
        List<PendingOrder> pending = tdb.paperDb.loadPendingOrders();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).side()).isEqualTo("BUY");
        assertThat(pending.get(0).symbol()).isEqualTo("600001.SH");
        assertThat(pending.get(0).signalDate()).isEqualTo(D);
        assertThat(pending.get(0).reasonJson()).isNotBlank();

        // D+1 日：挂单按开盘价成交；持仓买入日为 D+1，当日不允许卖出
        PaperTrader.DaySummary s2 = trader.runDay(D.plusDays(1));
        assertThat(s2.filledBuys()).isEqualTo(1);
        List<PaperPosition> positions = tdb.paperDb.loadPositions();
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).buyDate()).isEqualTo(D.plusDays(1));

        List<PendingOrder> after = tdb.paperDb.loadPendingOrders();
        assertThat(after.stream().filter(o -> o.side().equals("SELL"))).isEmpty();

        // 每笔成交都有理由且带 version_id
        List<PaperTrade> trades = tdb.paperDb.loadTradesOn(D.plusDays(1));
        assertThat(trades).hasSize(1);
        PaperTrade t = trades.get(0);
        assertThat(t.side()).isEqualTo("BUY");
        assertThat(t.signalDate()).isEqualTo(D);
        assertThat(t.reasonJson()).isNotBlank().contains("signal_type");
        assertThat(t.versionId()).isPositive();
        assertThat(t.realizedPnl()).isNull(); // 买入无实现盈亏（wasNull 必须紧跟 getDouble）
    }

    @Test
    void 止损触发卖出_理由带版本() throws Exception {
        LocalDate buyDay = D.minusDays(5);
        tdb.insertStock("600002.SH", "BBB", "银行", LocalDate.of(2020, 1, 1), false);
        // 120 根 bars，最后一根收盘 9.0（成本 10，止损线 9.2）
        double[][] rows = new double[120][5];
        for (int i = 0; i < 119; i++) {
            double c = 10.0 + i * 0.01;
            rows[i] = new double[]{c * 0.99, c * 1.01, c * 0.98, c, Double.NaN};
        }
        rows[119] = new double[]{9.5, 9.6, 8.9, 9.0, Double.NaN};
        tdb.insertBars("600002.SH", D, rows);

        tdb.paperDb.ensureAccount(100000, 1);
        tdb.paperDb.upsertPosition(new PaperPosition("600002.SH", "BBB", 1000,
                10.0, buyDay, "{\"signal_type\":\"BUY_CANDIDATE\"}", 1));

        PaperTrader trader = trader(Map.of());
        trader.runDay(D);

        List<PendingOrder> pending = tdb.paperDb.loadPendingOrders();
        assertThat(pending).hasSize(1);
        PendingOrder sell = pending.get(0);
        assertThat(sell.side()).isEqualTo("SELL");
        assertThat(sell.symbol()).isEqualTo("600002.SH");
        assertThat(sell.qty()).isEqualTo(1000);
        assertThat(sell.reasonJson()).contains("STOP_LOSS");
        assertThat(sell.versionId()).isPositive();
    }

    @Test
    void 资金不足按整手降级_不足一手则跳过() throws Exception {
        insertAAA(D);
        // 初始资金 500：单只预算 100 元，股价约 16 元 → 不足一手
        PaperTrader trader = trader(Map.of(
                StrategyConfig.FUSION_THRESHOLD, "55",
                StrategyConfig.PAPER_INITIAL_CAPITAL, "500"));
        trader.runDay(D);
        assertThat(tdb.paperDb.loadPendingOrders()).isEmpty();
        assertThat(tdb.paperDb.loadPositions()).isEmpty();
    }

    @Test
    void 涨停挂单无法成交_保留重试() throws Exception {
        // CCC 设为 ST：选股 L1 即过滤，避免 select() 产生干扰性候选
        tdb.insertStock("600003.SH", "CCC", "银行", LocalDate.of(2020, 1, 1), true);
        // D 日信号：用普通 bar；D+1 开盘涨停（pctChg=10）
        double[][] rowsD = new double[120][5];
        for (int i = 0; i < 120; i++) {
            double c = 10.0 + i * 0.01;
            rowsD[i] = new double[]{c * 0.99, c * 1.01, c * 0.98, c, Double.NaN};
        }
        tdb.insertBars("600003.SH", D, rowsD);
        tdb.insertBars("600003.SH", D.plusDays(1),
                new double[][]{{11.0, 11.0, 11.0, 11.0, 10.0}});

        PaperTrader trader = trader(Map.of());
        int v = tdb.cc.versionIdForDate(D);
        // 手工挂一个买单（信号日 D）
        tdb.paperDb.insertPendingOrder("600003.SH", "CCC", "BUY", 100, 10.0, D,
                "{\"signal_type\":\"BUY_CANDIDATE\"}", v);

        trader.runDay(D.plusDays(1));

        // 涨停买不到：订单仍为 PENDING，重试次数 +1，未成交
        List<PendingOrder> pending = tdb.paperDb.loadPendingOrders();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).retries()).isEqualTo(1);
        assertThat(tdb.paperDb.loadPositions()).isEmpty();
        assertThat(tdb.paperDb.loadTradesOn(D.plusDays(1))).isEmpty();
    }
}

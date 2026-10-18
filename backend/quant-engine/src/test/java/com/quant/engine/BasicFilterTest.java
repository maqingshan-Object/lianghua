package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.filter.BasicFilter;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Stock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** L1 基础过滤边界测试。 */
class BasicFilterTest {
    private TestDb tdb;
    private final LocalDate asOf = LocalDate.of(2025, 6, 30);
    private BasicFilter filter;
    private MarketDataService mds;

    @BeforeEach
    void setUp() throws Exception {
        tdb = new TestDb();
        tdb.initConfig(asOf);
        filter = new BasicFilter();
        mds = new MarketDataService(tdb.db);
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    private FilterResult run(Stock stock, List<Bar> bars) throws Exception {
        StrategyConfig cfg = tdb.cc.configForDate(asOf);
        return filter.filter(stock, bars, FundamentalSnapshot.empty(stock.symbol(), asOf), cfg, asOf, mds);
    }

    private Stock stock(String symbol, LocalDate listDate, boolean st, boolean suspended) {
        return new Stock(symbol, "测试", "SH", "银行", listDate, st, suspended);
    }

    private List<Bar> bars(double amount) {
        Bar b = new Bar("600001.SH", asOf, 10, 10.5, 9.8, 10.2, 100000, amount,
                1.0, 2.0, null, null, false);
        return List.of(b);
    }

    @Test
    void st股被剔除() throws Exception {
        FilterResult r = run(stock("600001.SH", LocalDate.of(2020, 1, 1), true, false), bars(1e8));
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("ST");
    }

    @Test
    void 停牌被剔除() throws Exception {
        FilterResult r = run(stock("600001.SH", LocalDate.of(2020, 1, 1), false, true), bars(1e8));
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("停牌");
    }

    @Test
    void 上市不满1年被剔除() throws Exception {
        // 上市 2025-01-01，asOf 2025-06-30，不满 1 年
        FilterResult r = run(stock("600001.SH", LocalDate.of(2025, 1, 1), false, false), bars(1e8));
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("不满");
    }

    @Test
    void 上市满1年边界通过() throws Exception {
        // 上市 2024-06-30，asOf 2025-06-30，恰满 1 年 → 通过
        FilterResult r = run(stock("600001.SH", LocalDate.of(2024, 6, 30), false, false), bars(1e8));
        assertThat(r.passed()).isTrue();
    }

    @Test
    void 日成交额低于下限被剔除() throws Exception {
        FilterResult r = run(stock("600001.SH", LocalDate.of(2020, 1, 1), false, false), bars(1e6));
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("成交额");
    }

    @Test
    void 全部正常通过() throws Exception {
        FilterResult r = run(stock("600001.SH", LocalDate.of(2020, 1, 1), false, false), bars(1e8));
        assertThat(r.passed()).isTrue();
    }

    @Test
    void 无行情数据被剔除() throws Exception {
        FilterResult r = run(stock("600001.SH", LocalDate.of(2020, 1, 1), false, false), List.of());
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("无行情");
    }
}

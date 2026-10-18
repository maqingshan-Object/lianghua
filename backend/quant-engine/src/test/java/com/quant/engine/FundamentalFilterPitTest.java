package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.filter.FundamentalFilter;
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

/**
 * L2 基本面过滤 + PIT 测试。
 * 关键断言：announce_date 在 asOf 之后的财报（未来才公告）绝不能被采用。
 */
class FundamentalFilterPitTest {
    private TestDb tdb;
    private final LocalDate asOf = LocalDate.of(2025, 4, 10);
    private FundamentalFilter filter;
    private MarketDataService mds;
    private StrategyConfig cfg;

    @BeforeEach
    void setUp() throws Exception {
        tdb = new TestDb();
        tdb.initConfig(asOf);
        filter = new FundamentalFilter();
        mds = new MarketDataService(tdb.db);
        cfg = tdb.cc.configForDate(asOf);
    }

    @AfterEach
    void tearDown() throws Exception {
        tdb.close();
    }

    private Stock stock(String symbol) {
        return new Stock(symbol, "测试", "SH", "银行", LocalDate.of(2020, 1, 1), false, false);
    }

    private List<Bar> bars() {
        return List.of(new Bar("X", asOf, 10, 10.5, 9.8, 10.2, 100000, 1e8, 1.0, 2.0, null, null, false));
    }

    @Test
    void 未来才公告的亏损财报不能被采用() throws Exception {
        // 已公告：2024Q4，ROE 15（盈利）；未公告：2025Q1，ROE -5（亏损），公告日在 asOf 之后
        tdb.insertFundamental("600001.SH", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 4, 1),
                15.0, 10.0, 12.0, 40.0, 1e9);
        tdb.insertFundamental("600001.SH", LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 15),
                -5.0, -8.0, -20.0, 45.0, -1e8);

        FundamentalSnapshot snap = mds.fundamental("600001.SH", asOf);
        // PIT 快照必须是 2024Q4 那期（当时可见）
        assertThat(snap.isPresent()).isTrue();
        assertThat(snap.latest().orElseThrow().roe()).isEqualTo(15.0);

        FilterResult r = filter.filter(stock("600001.SH"), bars(), snap, cfg, asOf, mds);
        assertThat(r.passed()).isTrue(); // 盈利 → 通过；若误用了未来亏损财报会被剔除
    }

    @Test
    void 已公告的亏损财报会被剔除() throws Exception {
        tdb.insertFundamental("600002.SH", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 4, 1),
                -3.0, -5.0, -15.0, 40.0, 1e8);
        FundamentalSnapshot snap = mds.fundamental("600002.SH", asOf);
        FilterResult r = filter.filter(stock("600002.SH"), bars(), snap, cfg, asOf, mds);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("亏损");
    }

    @Test
    void 经营现金流为负被剔除() throws Exception {
        tdb.insertFundamental("600003.SH", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 4, 1),
                10.0, 8.0, 9.0, 40.0, -5e8);
        FundamentalSnapshot snap = mds.fundamental("600003.SH", asOf);
        FilterResult r = filter.filter(stock("600003.SH"), bars(), snap, cfg, asOf, mds);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("现金流");
    }

    @Test
    void 资产负债率超限被剔除() throws Exception {
        tdb.insertFundamental("600004.SH", LocalDate.of(2024, 12, 31), LocalDate.of(2025, 4, 1),
                10.0, 8.0, 9.0, 85.0, 1e8);
        FundamentalSnapshot snap = mds.fundamental("600004.SH", asOf);
        FilterResult r = filter.filter(stock("600004.SH"), bars(), snap, cfg, asOf, mds);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("资产负债率");
    }

    @Test
    void 无财报时不误杀只标记通过() throws Exception {
        FundamentalSnapshot snap = mds.fundamental("600005.SH", asOf);
        assertThat(snap.isPresent()).isFalse();
        FilterResult r = filter.filter(stock("600005.SH"), bars(), snap, cfg, asOf, mds);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void 公告日期为空的财报视为不可见() throws Exception {
        tdb.insertFundamental("600006.SH", LocalDate.of(2024, 12, 31), null,
                -10.0, -5.0, -15.0, 40.0, -1e8);
        FundamentalSnapshot snap = mds.fundamental("600006.SH", asOf);
        assertThat(snap.isPresent()).isFalse(); // 保守：无法证明当时可见
    }
}

package com.quant.paper;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.paper.engine.Matcher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 撮合器单测：涨跌停/滑点/佣金保底。 */
class MatcherTest {
    private final Matcher matcher = new Matcher();
    private final StrategyConfig cfg = new StrategyConfig(StrategyConfig.defaults());

    private static Bar bar(double open, double pctChg) {
        return new Bar("600001.SH", LocalDate.of(2025, 6, 30),
                open, open, open, open, 100000, 1e8, pctChg, null, null, null, false);
    }

    @Test
    void 涨停买不到() {
        Matcher.FillResult r = matcher.tryFillBuy(bar(11.0, 10.0), false, 100, cfg);
        assertThat(r.filled()).isFalse();
        assertThat(r.failReason()).contains("涨停");
    }

    @Test
    void 跌停卖不出() {
        Matcher.FillResult r = matcher.tryFillSell(bar(9.0, -10.0), false, 100, cfg);
        assertThat(r.filled()).isFalse();
        assertThat(r.failReason()).contains("跌停");
    }

    @Test
    void ST股按5个点判定涨跌停() {
        assertThat(matcher.tryFillBuy(bar(10.5, 5.0), true, 100, cfg).filled()).isFalse();
        assertThat(matcher.tryFillSell(bar(9.5, -5.0), true, 100, cfg).filled()).isFalse();
        // 5% 对非 ST 不算涨停
        assertThat(matcher.tryFillBuy(bar(10.5, 5.0), false, 100, cfg).filled()).isTrue();
    }

    @Test
    void 停牌作废() {
        Bar suspended = new Bar("600001.SH", LocalDate.of(2025, 6, 30),
                10, 10, 10, 10, 0, 0, null, null, null, null, true);
        assertThat(matcher.tryFillBuy(suspended, false, 100, cfg).filled()).isFalse();
        assertThat(matcher.tryFillSell(suspended, false, 100, cfg).filled()).isFalse();
    }

    @Test
    void 滑点双边各千分之一() {
        // 默认滑点 0.1%：买入开盘价上浮，卖出下浮
        Matcher.FillResult buy = matcher.tryFillBuy(bar(10.0, 1.0), false, 100, cfg);
        Matcher.FillResult sell = matcher.tryFillSell(bar(10.0, 1.0), false, 100, cfg);
        assertThat(buy.filled()).isTrue();
        assertThat(sell.filled()).isTrue();
        assertThat(buy.price()).isEqualTo(10.01);
        assertThat(sell.price()).isEqualTo(9.99);
    }

    @Test
    void 佣金万二点五且五元保底() {
        // 小额：按保底 5 元
        Matcher.FillResult small = matcher.tryFillBuy(bar(10.0, 1.0), false, 100, cfg);
        assertThat(small.commission()).isEqualTo(5.0);
        // 大额：100000 股 @10.01 = 1001000 元 * 0.00025 = 250.25
        Matcher.FillResult big = matcher.tryFillBuy(bar(10.0, 1.0), false, 100000, cfg);
        assertThat(big.commission()).isEqualTo(250.25);
        // 卖出带印花税千分之一：100 股 @9.99 = 999 元 * 0.001 ≈ 1.0
        Matcher.FillResult sell = matcher.tryFillSell(bar(10.0, 1.0), false, 100, cfg);
        assertThat(sell.stampTax()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void 滑点可配() {
        StrategyConfig cfg2 = new StrategyConfig(Map.of(
                StrategyConfig.PAPER_SLIPPAGE_PCT, "0.5",
                StrategyConfig.PAPER_COMMISSION_RATE, "0.00025",
                StrategyConfig.PAPER_COMMISSION_MIN, "5",
                StrategyConfig.PAPER_STAMP_TAX_RATE, "0.001"));
        Matcher.FillResult buy = matcher.tryFillBuy(bar(10.0, 1.0), false, 100, cfg2);
        assertThat(buy.price()).isEqualTo(10.05);
    }
}

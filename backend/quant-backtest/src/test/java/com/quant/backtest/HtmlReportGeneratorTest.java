package com.quant.backtest;

import com.quant.backtest.model.BacktestResult;
import com.quant.backtest.report.HtmlReportGenerator;
import com.quant.paper.model.PaperTrade;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告生成测试：结构完整 / 合成数据标注 / NaN 不泄露为 "NaN" 字符串。
 */
class HtmlReportGeneratorTest {

    private BacktestResult sample(boolean synthetic) {
        LocalDate d0 = LocalDate.of(2025, 1, 1);
        List<LocalDate> dates = List.of(d0, d0.plusDays(1), d0.plusDays(2));
        List<Double> navs = List.of(100000.0, 101000.0, 100500.0);
        Map<Integer, List<Double>> holding = new LinkedHashMap<>();
        holding.put(5, List.of(1.2, -0.5));
        holding.put(10, List.of());
        holding.put(20, List.of(3.1));
        BacktestResult.SliceMetrics in = new BacktestResult.SliceMetrics(
                2, 1.0, 50.0, -0.5, 1.2, 100.0);
        BacktestResult.SliceMetrics out = new BacktestResult.SliceMetrics(
                1, -0.5, -70.0, -0.5, Double.NaN, Double.NaN);
        return new BacktestResult(
                d0, d0.plusDays(2), 2, 100000.0,
                dates, navs,
                true, List.of(100000.0, 100200.0, 100100.0), 0.1, 0.4,
                0.5, 60.0, -0.5, d0.plusDays(1), d0.plusDays(2), Double.NaN,
                100.0, 2.5, 3, 2, 4.0,
                holding, in, out,
                1, List.of("单日亏损超过熔断线，当天停开新仓"), 0,
                List.of("合成数据回放", "费用含佣金/印花税/滑点"),
                synthetic);
    }

    private List<PaperTrade> sampleTrades() {
        LocalDate d = LocalDate.of(2025, 1, 1);
        return List.of(
                new PaperTrade(1, d, d.plusDays(1), "600001.SH", "BUY", 10.0, 1000,
                        5.0, 0.0, "{\"signal_type\":\"BUY_CANDIDATE\"}", 2, null),
                new PaperTrade(2, d.plusDays(1), d.plusDays(2), "600001.SH", "SELL", 10.5, 1000,
                        5.0, 10.5, "{\"signal_type\":\"TAKE_PROFIT\"}", 2, 484.5));
    }

    @Test
    void 报告结构完整_合成数据明确标注() {
        String html = new HtmlReportGenerator().generate(sample(true), sampleTrades());

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("合成数据回放，仅验证逻辑，不代表任何真实收益");
        assertThat(html).contains("cdn.jsdelivr.net/npm/echarts");
        assertThat(html).contains("沪深300");
        assertThat(html).contains("假设声明");
        assertThat(html).contains("逐笔交易明细");
        assertThat(html).contains("风控拦截统计");
        assertThat(html).contains("600001.SH");
        assertThat(html).contains("不构成投资建议");
        // NaN 的夏普/样本外胜率必须显示为 —，不能出现 "NaN" 裸字符串
        assertThat(html).doesNotContain("NaN");
    }

    @Test
    void 非合成数据不带合成标注() {
        String html = new HtmlReportGenerator().generate(sample(false), List.of());
        assertThat(html).doesNotContain("合成数据回放，仅验证逻辑");
        assertThat(html).contains("区间内无成交");
    }

    @Test
    void 理由中的HTML特殊字符被转义() {
        LocalDate d = LocalDate.of(2025, 1, 1);
        PaperTrade t = new PaperTrade(1, d, d, "A", "BUY", 10.0, 100,
                5.0, 0.0, "{\"note\":\"<script>alert(1)</script>\"}", 1, null);
        String html = new HtmlReportGenerator().generate(sample(true), List.of(t));
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;");
    }
}

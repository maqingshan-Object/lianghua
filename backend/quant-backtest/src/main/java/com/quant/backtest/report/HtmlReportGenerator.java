package com.quant.backtest.report;

import com.quant.backtest.model.BacktestResult;
import com.quant.paper.model.PaperTrade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单文件 HTML 回测报告生成器。
 *
 * - 图表用 ECharts CDN（用户在自己电脑浏览器打开，有网即可）；
 * - ECharts 加载失败时自动降级：图表区显示提示，文字版数据表格始终存在；
 * - 所有数字来自 BacktestResult（真实计算），本类只做展示，不编造。
 */
public class HtmlReportGenerator {
    private static final String ECHARTS_CDN =
            "https://cdn.jsdelivr.net/npm/echarts@5.5.1/dist/echarts.min.js";

    public String generate(BacktestResult r, List<PaperTrade> trades) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n");
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        h.append("<title>回测报告 ").append(r.from()).append(" ~ ").append(r.to()).append("</title>\n");
        h.append("<script src=\"").append(ECHARTS_CDN).append("\"></script>\n");
        h.append("<style>\n").append(css()).append("</style>\n</head>\n<body>\n");

        h.append("<h1>回测报告</h1>\n");
        if (r.syntheticData()) {
            h.append("<div class=\"banner\">合成数据回放，仅验证逻辑，不代表任何真实收益。</div>\n");
        }
        h.append(basicInfo(r));
        h.append(coreMetrics(r));
        h.append(sampleSplit(r));
        h.append(chartsSection(r));
        h.append(tradesSection(trades));
        h.append(riskSection(r));
        h.append(assumptionsSection(r));
        h.append("<footer>声明：本报告由模拟回放生成，仅供研究，不构成投资建议。")
                .append("历史表现不代表未来收益；模拟交易盈利不代表实盘能够盈利。</footer>\n");

        h.append(chartScript(r));
        h.append("</body>\n</html>\n");
        return h.toString();
    }

    // ---------------- 样式 ----------------

    private String css() {
        return """
                body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
                  max-width:1080px;margin:0 auto;padding:16px;color:#222;background:#fafafa}
                h1{font-size:22px} h2{font-size:17px;margin-top:28px;
                  border-left:4px solid #2563eb;padding-left:8px}
                table{border-collapse:collapse;width:100%;font-size:13px;background:#fff}
                th,td{border:1px solid #e5e7eb;padding:6px 8px;text-align:left}
                th{background:#f3f4f6} tr:nth-child(even) td{background:#fafafa}
                .banner{background:#fef3c7;border:1px solid #f59e0b;color:#92400e;
                  padding:10px 14px;border-radius:6px;margin:12px 0;font-weight:bold}
                .chart{width:100%;height:340px;background:#fff;border:1px solid #e5e7eb;
                  border-radius:6px;margin:8px 0}
                .pos{color:#dc2626} .neg{color:#16a34a} .na{color:#9ca3af}
                footer{margin-top:32px;font-size:12px;color:#6b7280;
                  border-top:1px solid #e5e7eb;padding-top:12px}
                .assump li{margin:4px 0;font-size:13px}
                .reason{max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                """;
    }

    // ---------------- 各节 ----------------

    private String basicInfo(BacktestResult r) {
        return "<h2>基本信息</h2>\n<table>\n"
                + row("回测区间", r.from() + " ~ " + r.to())
                + row("交易日天数", String.valueOf(r.navDates().size()))
                + row("初始虚拟资金", fmt2(r.initialCapital()) + " 元")
                + row("策略配置版本", "v" + r.pinnedVersionNo() + "（回放前锁定，全区间同一版本）")
                + row("成交笔数", r.tradeCount() + "（买入/卖出合计）")
                + row("基准", r.benchmarkAvailable() ? "沪深300（000300.SH），按初始资金归一化"
                        : "<span class=\"na\">基准数据缺失，未做对比</span>")
                + "</table>\n";
    }

    private String coreMetrics(BacktestResult r) {
        StringBuilder t = new StringBuilder("<h2>核心绩效指标（含费用口径）</h2>\n<table>\n");
        t.append(row("总收益率", pct(r.totalReturnPct())));
        t.append(row("年化收益率（250交易日/年）", pct(r.annualizedPct())));
        t.append(row("最大回撤", pct(r.maxDrawdownPct())
                + (r.ddStart() != null ? "（" + r.ddStart() + " → " + r.ddEnd() + "）" : "")));
        t.append(row("夏普比率（无风险利率=0）", fmt2(r.sharpe())));
        t.append(row("胜率（盈利卖出/有盈亏卖出）", pct(r.winRatePct())));
        t.append(row("盈亏比（平均盈利/平均亏损）", fmt2(r.profitLossRatio())));
        t.append(row("卖出笔数", String.valueOf(r.sellCount())));
        t.append(row("平均持仓天数（股数加权FIFO）", fmt2(r.avgHoldingDays()) + " 天"));
        if (r.benchmarkAvailable()) {
            t.append(row("沪深300同期收益", pct(r.benchmarkReturnPct())));
            t.append(row("超额收益（策略-基准）", pct(r.excessReturnPct())));
        }
        t.append("</table>\n");
        return t.toString();
    }

    private String sampleSplit(BacktestResult r) {
        StringBuilder t = new StringBuilder(
                "<h2>样本内 / 样本外（按时间 8:2 划分，样本外不参与调参）</h2>\n<table>\n");
        t.append("<tr><th></th><th>天数</th><th>总收益率</th><th>年化</th><th>最大回撤</th>"
                + "<th>夏普</th><th>胜率</th></tr>\n");
        t.append(sliceRow("样本内", r.inSample()));
        t.append(sliceRow("样本外", r.outSample()));
        t.append("</table>\n<p style=\"font-size:12px;color:#6b7280\">")
                .append("说明：本回测全程使用锁定版本，未做任何参数寻优；此处仅做指标切片展示，")
                .append("若样本外明显差于样本内，应首先怀疑过拟合。</p>\n");
        return t.toString();
    }

    private String sliceRow(String name, BacktestResult.SliceMetrics s) {
        if (s == null) {
            return "";
        }
        return "<tr><td>" + name + "</td><td>" + s.days() + "</td><td>" + pct(s.totalReturnPct())
                + "</td><td>" + pct(s.annualizedPct()) + "</td><td>" + pct(s.maxDrawdownPct())
                + "</td><td>" + fmt2(s.sharpe()) + "</td><td>" + pct(s.winRatePct()) + "</td></tr>\n";
    }

    private String chartsSection(BacktestResult r) {
        StringBuilder t = new StringBuilder("<h2>曲线与分布</h2>\n");
        t.append("<div id=\"navChart\" class=\"chart\"></div>\n");
        t.append("<div id=\"ddChart\" class=\"chart\"></div>\n");
        t.append("<div id=\"histChart\" class=\"chart\"></div>\n");
        t.append("<div id=\"chartFallback\" style=\"display:none\" class=\"banner\">")
                .append("ECharts 未能加载（可能无网络），图表无法渲染，以下文字版数据表格可作为替代。</div>\n");
        // 降级文字版：净值表（每 5 个交易日抽样，避免过长）
        t.append("<details><summary>净值曲线数据（文字版）</summary>\n<table>\n"
                + "<tr><th>日期</th><th>策略净值</th>"
                + (r.benchmarkAvailable() ? "<th>沪深300净值</th>" : "") + "<th>回撤%</th></tr>\n");
        List<Double> dd = drawdownSeries(r.navValues());
        for (int i = 0; i < r.navDates().size(); i += Math.max(1, r.navDates().size() / 60)) {
            t.append("<tr><td>").append(r.navDates().get(i)).append("</td><td>")
                    .append(fmt2(r.navValues().get(i))).append("</td>");
            if (r.benchmarkAvailable()) {
                t.append("<td>").append(fmt2(r.benchmarkValues().get(i))).append("</td>");
            }
            t.append("<td>").append(fmt2(dd.get(i))).append("%</td></tr>\n");
        }
        t.append("</table></details>\n");
        // 持有期收益分布文字版
        t.append("<details><summary>持有期收益分布（文字版）</summary>\n<table>\n"
                + "<tr><th>持有期</th><th>样本数</th><th>均值</th><th>中位数</th>"
                + "<th>最小</th><th>最大</th><th>胜率(>0)</th></tr>\n");
        for (Map.Entry<Integer, List<Double>> e : r.holdingReturns().entrySet()) {
            List<Double> v = e.getValue();
            t.append("<tr><td>").append(e.getKey()).append(" 天</td><td>").append(v.size())
                    .append("</td><td>").append(pct(mean(v))).append("</td><td>")
                    .append(pct(median(v))).append("</td><td>").append(pct(min(v)))
                    .append("</td><td>").append(pct(max(v))).append("</td><td>")
                    .append(pct(winRateOf(v))).append("</td></tr>\n");
        }
        t.append("</table></details>\n");
        return t.toString();
    }

    private String tradesSection(List<PaperTrade> trades) {
        StringBuilder t = new StringBuilder("<h2>逐笔交易明细（")
                .append(trades.size()).append(" 笔）</h2>\n");
        if (trades.isEmpty()) {
            return t + "<p class=\"na\">区间内无成交。</p>\n";
        }
        t.append("<table>\n<tr><th>信号日</th><th>成交日</th><th>代码</th><th>方向</th>"
                + "<th>价格</th><th>数量</th><th>佣金</th><th>印花税</th>"
                + "<th>实现盈亏</th><th>版本</th><th>理由</th></tr>\n");
        for (PaperTrade tr : trades) {
            t.append("<tr><td>").append(tr.signalDate()).append("</td><td>").append(tr.tradeDate())
                    .append("</td><td>").append(esc(tr.symbol())).append("</td><td>")
                    .append("BUY".equals(tr.side()) ? "<span class=\"pos\">买入</span>"
                            : "<span class=\"neg\">卖出</span>")
                    .append("</td><td>").append(fmt2(tr.price())).append("</td><td>").append(tr.qty())
                    .append("</td><td>").append(fmt2(tr.commission())).append("</td><td>")
                    .append(fmt2(tr.stampTax())).append("</td><td>")
                    .append(tr.realizedPnl() == null ? "<span class=\"na\">—</span>"
                            : pctPlain(tr.realizedPnl()))
                    .append("</td><td>v").append(tr.versionId()).append("</td><td class=\"reason\" title=\"")
                    .append(esc(tr.reasonJson())).append("\">").append(esc(truncate(tr.reasonJson(), 80)))
                    .append("</td></tr>\n");
        }
        t.append("</table>\n");
        return t.toString();
    }

    private String riskSection(BacktestResult r) {
        StringBuilder t = new StringBuilder("<h2>风控拦截统计</h2>\n<table>\n");
        t.append(row("停开新仓天数", String.valueOf(r.haltDays())));
        t.append(row("作废挂单数（涨跌停/停牌超重试）", String.valueOf(r.cancelledOrders())));
        t.append("</table>\n");
        if (!r.haltReasons().isEmpty()) {
            t.append("<ul>\n");
            for (String reason : r.haltReasons()) {
                t.append("<li>").append(esc(reason)).append("</li>\n");
            }
            t.append("</ul>\n");
        }
        return t.toString();
    }

    private String assumptionsSection(BacktestResult r) {
        StringBuilder t = new StringBuilder("<h2>假设声明（诚实声明比漂亮数字重要）</h2>\n<ul class=\"assump\">\n");
        for (String a : r.assumptions()) {
            t.append("<li>").append(esc(a)).append("</li>\n");
        }
        t.append("</ul>\n");
        return t.toString();
    }

    // ---------------- 图表脚本 ----------------

    private String chartScript(BacktestResult r) {
        List<Double> dd = drawdownSeries(r.navValues());
        StringBuilder s = new StringBuilder("<script>\n(function(){\n");
        s.append("var DATES=").append(jsStrArr(r.navDates())).append(";\n");
        s.append("var NAV=").append(jsNumArr(r.navValues())).append(";\n");
        s.append("var DD=").append(jsNumArr(dd)).append(";\n");
        s.append("var HAS_BENCH=").append(r.benchmarkAvailable()).append(";\n");
        s.append("var BENCH=").append(jsNumArr(r.benchmarkAvailable() ? r.benchmarkValues() : List.of())).append(";\n");
        s.append("var HIST=").append(histogramJson(r.holdingReturns())).append(";\n");
        s.append("""
                try {
                  if (typeof echarts === 'undefined') throw new Error('echarts missing');
                  var nav = echarts.init(document.getElementById('navChart'));
                  var series = [{name:'策略净值', type:'line', showSymbol:false, data:NAV}];
                  if (HAS_BENCH) series.push({name:'沪深300', type:'line', showSymbol:false, data:BENCH});
                  nav.setOption({title:{text:'净值曲线'}, tooltip:{trigger:'axis'},
                    legend:{data: HAS_BENCH ? ['策略净值','沪深300'] : ['策略净值']},
                    xAxis:{type:'category', data:DATES}, yAxis:{type:'value', scale:true},
                    series: series});
                  var dd = echarts.init(document.getElementById('ddChart'));
                  dd.setOption({title:{text:'回撤曲线（%）'}, tooltip:{trigger:'axis'},
                    xAxis:{type:'category', data:DATES}, yAxis:{type:'value'},
                    series:[{name:'回撤%', type:'line', showSymbol:false, areaStyle:{}, data:DD}]});
                  var hist = echarts.init(document.getElementById('histChart'));
                  var hSeries = HIST.bins.map(function(){ return 0; });
                  var hs = Object.keys(HIST.counts).map(function(k){
                    return {name:'持有'+k+'天', type:'bar', data:HIST.counts[k]}; });
                  hist.setOption({title:{text:'持有期收益率分布（%）'}, tooltip:{trigger:'axis'},
                    legend:{data: Object.keys(HIST.counts).map(function(k){return '持有'+k+'天';})},
                    xAxis:{type:'category', data:HIST.bins}, yAxis:{type:'value'}, series:hs});
                  window.addEventListener('resize', function(){
                    nav.resize(); dd.resize(); hist.resize(); });
                } catch (e) {
                  document.getElementById('chartFallback').style.display = 'block';
                }
                })();
                </script>
                """);
        return s.toString();
    }

    /** 持有期收益直方图：-20%~+20% 按 2% 分箱，另加两端溢出箱。 */
    private String histogramJson(Map<Integer, List<Double>> holdingReturns) {
        List<String> bins = new ArrayList<>();
        bins.add("<-20");
        for (int b = -20; b < 20; b += 2) {
            bins.add(b + "~" + (b + 2));
        }
        bins.add(">20");
        StringBuilder s = new StringBuilder("{\"bins\":[");
        for (int i = 0; i < bins.size(); i++) {
            if (i > 0) s.append(",");
            s.append("\"").append(bins.get(i)).append("\"");
        }
        s.append("],\"counts\":{");
        boolean first = true;
        for (Map.Entry<Integer, List<Double>> e : holdingReturns.entrySet()) {
            if (!first) s.append(",");
            first = false;
            int[] counts = new int[bins.size()];
            for (double v : e.getValue()) {
                int idx;
                if (v < -20) idx = 0;
                else if (v >= 20) idx = bins.size() - 1;
                else idx = 1 + (int) ((v + 20) / 2);
                counts[Math.min(idx, bins.size() - 1)]++;
            }
            s.append("\"").append(e.getKey()).append("\":[");
            for (int i = 0; i < counts.length; i++) {
                if (i > 0) s.append(",");
                s.append(counts[i]);
            }
            s.append("]");
        }
        return s.append("}}").toString();
    }

    // ---------------- 小工具 ----------------

    private static List<Double> drawdownSeries(List<Double> navs) {
        List<Double> out = new ArrayList<>();
        double peak = Double.NEGATIVE_INFINITY;
        for (double v : navs) {
            if (v > peak) peak = v;
            out.add(peak > 0 ? (v - peak) / peak * 100.0 : 0);
        }
        return out;
    }

    private static double mean(List<Double> v) {
        return v.isEmpty() ? Double.NaN
                : v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static double min(List<Double> v) {
        return v.isEmpty() ? Double.NaN : v.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    private static double max(List<Double> v) {
        return v.isEmpty() ? Double.NaN : v.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static double winRateOf(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        long w = v.stream().filter(x -> x > 0).count();
        return (double) w / v.size() * 100.0;
    }

    private static String row(String k, String v) {
        return "<tr><th style=\"width:220px\">" + esc(k) + "</th><td>" + v + "</td></tr>\n";
    }

    /** 允许 v 传入已转义的 HTML 片段（如带颜色 span 的 pct）。 */
    private static String fmt2(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "<span class=\"na\">—</span>"
                : String.format("%.2f", v);
    }

    private static String pct(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "<span class=\"na\">—</span>";
        }
        String cls = v > 0 ? "pos" : (v < 0 ? "neg" : "");
        return "<span class=\"" + cls + "\">" + String.format("%+.2f%%", v) + "</span>";
    }

    private static String pctPlain(double v) {
        String cls = v > 0 ? "pos" : (v < 0 ? "neg" : "");
        return "<span class=\"" + cls + "\">" + String.format("%+.2f", v) + "</span>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String jsStrArr(List<LocalDate> dates) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < dates.size(); i++) {
            if (i > 0) s.append(",");
            s.append("\"").append(dates.get(i)).append("\"");
        }
        return s.append("]").toString();
    }

    private static String jsNumArr(List<Double> vals) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) s.append(",");
            double v = vals.get(i);
            s.append(Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format("%.4f", v));
        }
        return s.append("]").toString();
    }
}

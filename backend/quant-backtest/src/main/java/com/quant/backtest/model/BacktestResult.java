package com.quant.backtest.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 回测结果容器。
 *
 * 铁律：所有数字均来自真实计算（回放记录 + 指标公式），绝不编造。
 * 合成数据验证时，syntheticData=true，报告与日志必须明确标注
 * "合成数据，仅验证逻辑"。
 */
public record BacktestResult(
        LocalDate from,
        LocalDate to,
        int pinnedVersionNo,
        double initialCapital,
        List<LocalDate> navDates,
        List<Double> navValues,
        // ---- 基准（沪深300） ----
        boolean benchmarkAvailable,
        List<Double> benchmarkValues,
        double benchmarkReturnPct,
        double excessReturnPct,
        // ---- 核心绩效指标 ----
        double totalReturnPct,
        double annualizedPct,
        double maxDrawdownPct,
        LocalDate ddStart,
        LocalDate ddEnd,
        double sharpe,
        double winRatePct,
        double profitLossRatio,
        int tradeCount,
        int sellCount,
        double avgHoldingDays,
        // ---- 持有期收益分布（key: 5/10/20 天 → 收益率% 列表） ----
        Map<Integer, List<Double>> holdingReturns,
        // ---- 样本内 / 样本外（默认 8:2 时间划分） ----
        SliceMetrics inSample,
        SliceMetrics outSample,
        // ---- 风控拦截统计 ----
        int haltDays,
        List<String> haltReasons,
        int cancelledOrders,
        // ---- 说明 ----
        List<String> assumptions,
        boolean syntheticData) {

    /** 样本内/外切片指标。 */
    public record SliceMetrics(
            int days,
            double totalReturnPct,
            double annualizedPct,
            double maxDrawdownPct,
            double sharpe,
            double winRatePct) {
    }
}

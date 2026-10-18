package com.quant.engine.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次选股运行的候选股（输出给 CLI / 看板 / select_candidates 表）。
 * 双通道分数与各因子分项全部保留。
 */
public record Candidate(
        String symbol,
        String name,
        String industry,
        double finalScore,
        Double aiScore,        // AI 通道未启用时为 null
        double engineScore,
        Map<String, Double> factorScores, // strategyId -> 0-100
        String reason,         // 入选理由（自然语言，多条拼接）
        double buyLow,         // 参考买入区间下沿
        double buyHigh,        // 参考买入区间上沿
        double stopLoss,       // 止损位
        double target          // 目标位
) {
    public Candidate {
        factorScores = new LinkedHashMap<>(factorScores);
    }
}

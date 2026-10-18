package com.quant.engine.model;

/**
 * 单个策略的输出：0-100 分 + 理由文本。
 * score 约定：0 = 无信号/数据不足；100 = 信号最强。
 */
public record Signal(
        String strategyId,
        double score,
        String reason
) {
    public Signal {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score 必须在 0-100 之间: " + score);
        }
    }

    public static Signal none(String strategyId, String reason) {
        return new Signal(strategyId, 0.0, reason);
    }

    public boolean hasSignal() {
        return score > 0;
    }
}

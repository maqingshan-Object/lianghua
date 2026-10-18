package com.quant.engine.scoring;

/** 双通道模式开关（配置项 fusion.mode）。 */
public enum FusionMode {
    /** 只用确定性引擎分数（默认，最稳）。 */
    ENGINE_ONLY,
    /** 只用 AI 分数。 */
    AI_ONLY,
    /** 加权融合：final = ai×w1 + engine×w2。 */
    FUSION;

    public static FusionMode parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 fusion.mode: " + s);
        }
    }
}

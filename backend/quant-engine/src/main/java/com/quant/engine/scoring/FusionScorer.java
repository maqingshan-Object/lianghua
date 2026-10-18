package com.quant.engine.scoring;

import com.quant.engine.config.StrategyConfig;

/**
 * 双通道融合打分（设计方案 3.7）。
 * <p>
 * final = ai_score × ai_weight + engine_score × engine_weight（FUSION 模式），
 * final >= threshold 则入选。threshold 即风险刻度：越高越保守。
 * <p>
 * 双分并存：无论哪种模式，ai_score 与 engine_score 都保留在输出里，
 * 开关只决定"按谁的结论入选"，不隐藏另一方的分数。
 */
public class FusionScorer {

    public record FusionInput(double aiScore, double engineScore, boolean aiAvailable) {}

    public record FusionResult(
            double aiScore,
            double engineScore,
            double finalScore,
            boolean selected,
            FusionMode mode) {}

    public FusionResult fuse(FusionInput in, StrategyConfig cfg) {
        FusionMode mode = FusionMode.parse(cfg.get(StrategyConfig.FUSION_MODE));
        double threshold = cfg.getDouble(StrategyConfig.FUSION_THRESHOLD);
        double finalScore = switch (mode) {
            case ENGINE_ONLY -> in.engineScore();
            case AI_ONLY -> {
                if (!in.aiAvailable()) {
                    throw new IllegalStateException("AI_ONLY 模式但 AI 分数不可用（AI 调用失败应先降级为 ENGINE_ONLY）");
                }
                yield in.aiScore();
            }
            case FUSION -> {
                double ai = in.aiAvailable() ? in.aiScore() : 0.0;
                double w1 = cfg.getDouble(StrategyConfig.FUSION_AI_WEIGHT);
                double w2 = cfg.getDouble(StrategyConfig.FUSION_ENGINE_WEIGHT);
                yield ai * w1 + in.engineScore() * w2;
            }
        };
        return new FusionResult(in.aiScore(), in.engineScore(), finalScore,
                finalScore >= threshold, mode);
    }
}

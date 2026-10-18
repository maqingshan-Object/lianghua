package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.scoring.FusionMode;
import com.quant.engine.scoring.FusionScorer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 融合公式测试（含需求示例：AI100/引擎50/权重对半/阈值60 → 75分入选）。 */
class FusionScorerTest {
    private final FusionScorer scorer = new FusionScorer();

    private StrategyConfig cfg(String mode, double aiW, double engW, double threshold) {
        Map<String, String> m = new LinkedHashMap<>(StrategyConfig.defaults());
        m.put(StrategyConfig.FUSION_MODE, mode);
        m.put(StrategyConfig.FUSION_AI_WEIGHT, String.valueOf(aiW));
        m.put(StrategyConfig.FUSION_ENGINE_WEIGHT, String.valueOf(engW));
        m.put(StrategyConfig.FUSION_THRESHOLD, String.valueOf(threshold));
        return new StrategyConfig(m);
    }

    @Test
    void 需求示例_AI100引擎50对半阈值60入选() {
        FusionScorer.FusionResult r = scorer.fuse(
                new FusionScorer.FusionInput(100, 50, true),
                cfg("FUSION", 0.5, 0.5, 60));
        assertThat(r.finalScore()).isEqualTo(75.0);
        assertThat(r.selected()).isTrue();
        assertThat(r.mode()).isEqualTo(FusionMode.FUSION);
        // 双分并存
        assertThat(r.aiScore()).isEqualTo(100.0);
        assertThat(r.engineScore()).isEqualTo(50.0);
    }

    @Test
    void 阈值80时不入选() {
        FusionScorer.FusionResult r = scorer.fuse(
                new FusionScorer.FusionInput(100, 50, true),
                cfg("FUSION", 0.5, 0.5, 80));
        assertThat(r.finalScore()).isEqualTo(75.0);
        assertThat(r.selected()).isFalse();
    }

    @Test
    void 纯引擎模式只看引擎分() {
        FusionScorer.FusionResult r = scorer.fuse(
                new FusionScorer.FusionInput(100, 50, true),
                cfg("ENGINE_ONLY", 0.5, 0.5, 60));
        assertThat(r.finalScore()).isEqualTo(50.0);
        assertThat(r.selected()).isFalse(); // 50 < 60
    }

    @Test
    void 纯AI模式只看AI分() {
        FusionScorer.FusionResult r = scorer.fuse(
                new FusionScorer.FusionInput(100, 50, true),
                cfg("AI_ONLY", 0.5, 0.5, 60));
        assertThat(r.finalScore()).isEqualTo(100.0);
        assertThat(r.selected()).isTrue();
    }

    @Test
    void AI不可用时融合按引擎分继续() {
        // 对应 SelectorEngine 的失败降级：aiAvailable=false 时 AI 按 0 计
        FusionScorer.FusionResult r = scorer.fuse(
                new FusionScorer.FusionInput(0, 70, false),
                cfg("FUSION", 0.5, 0.5, 60));
        assertThat(r.finalScore()).isEqualTo(35.0);
        assertThat(r.selected()).isFalse();
    }

    @Test
    void 非法模式抛异常() {
        assertThatThrownBy(() -> FusionMode.parse("RANDOM"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

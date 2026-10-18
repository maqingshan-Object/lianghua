package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.strategy.SignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 策略可插拔：注册/注销/启停。 */
class StrategyRegistryTest {

    private StrategyConfig cfgWith(Map<String, String> overrides) {
        Map<String, String> m = new LinkedHashMap<>(StrategyConfig.defaults());
        m.putAll(overrides);
        return new StrategyConfig(m);
    }

    @Test
    void 默认注册4个技术面策略() {
        StrategyRegistry r = new StrategyRegistry();
        assertThat(r.all()).hasSize(4);
        assertThat(r.all()).extracting(SignalStrategy::id)
                .containsExactlyInAnyOrder("ma_alignment", "breakout", "volume_surge", "pullback");
    }

    @Test
    void 关闭一个策略后不再参与() {
        StrategyRegistry r = new StrategyRegistry();
        StrategyConfig cfg = cfgWith(Map.of("strategy.breakout.enabled", "false"));
        assertThat(r.enabled(cfg)).hasSize(3);
        assertThat(r.enabled(cfg)).extracting(SignalStrategy::id).doesNotContain("breakout");
    }

    @Test
    void 可动态注册新策略() {
        StrategyRegistry r = new StrategyRegistry();
        int before = r.all().size();
        r.register(new com.quant.engine.strategy.MaAlignmentStrategy() {
            @Override public String id() { return "custom_demo"; }
            @Override public String name() { return "演示策略"; }
        });
        assertThat(r.all()).hasSize(before + 1);
        r.unregister("custom_demo");
        assertThat(r.all()).hasSize(before);
    }
}

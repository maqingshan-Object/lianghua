package com.quant.engine;

import com.quant.engine.config.StrategyConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置快照 JSON 序列化往返。 */
class StrategyConfigJsonTest {
    @Test
    void toJson_fromJson_往返一致() {
        Map<String, String> defaults = StrategyConfig.defaults();
        String json = StrategyConfig.toJson(defaults);
        Map<String, String> back = StrategyConfig.fromJson(json);
        assertThat(back).isEqualTo(defaults);
    }

    @Test
    void fromJson_兼容带空格的外部JSON() {
        // Python json.dumps 风格：{"a": "1", "b": "x"}
        Map<String, String> m = StrategyConfig.fromJson("{\"fusion.mode\": \"FUSION\", \"fusion.threshold\": \"60\"}");
        assertThat(m).containsEntry("fusion.mode", "FUSION").containsEntry("fusion.threshold", "60");
        assertThat(StrategyConfig.fromJson("{}")).isEmpty();
        assertThat(StrategyConfig.fromJson("  {  }  ")).isEmpty();
    }

    @Test
    void diff_能找出改动项() {
        StrategyConfig a = new StrategyConfig(StrategyConfig.defaults());
        Map<String, String> m = StrategyConfig.defaults();
        m.put(StrategyConfig.FUSION_THRESHOLD, "80");
        StrategyConfig b = new StrategyConfig(m);
        assertThat(a.diff(b)).contains("fusion.threshold: 60 -> 80");
        assertThat(a.diff(a)).isEmpty();
    }
}

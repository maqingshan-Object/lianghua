package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略注册表：可插拔。新增策略只需实现 SignalStrategy 并 register()，
 * 启停与权重走配置项 strategy.<id>.enabled / strategy.<id>.weight。
 */
public class StrategyRegistry {
    private final Map<String, SignalStrategy> strategies = new LinkedHashMap<>();

    public StrategyRegistry() {
        register(new MaAlignmentStrategy());
        register(new BreakoutStrategy());
        register(new VolumeSurgeStrategy());
        register(new PullbackStabilizeStrategy());
    }

    public void register(SignalStrategy s) {
        strategies.put(s.id(), s);
    }

    public void unregister(String id) {
        strategies.remove(id);
    }

    public List<SignalStrategy> all() {
        return new ArrayList<>(strategies.values());
    }

    /** 按配置过滤出启用的策略。 */
    public List<SignalStrategy> enabled(StrategyConfig cfg) {
        List<SignalStrategy> out = new ArrayList<>();
        for (SignalStrategy s : strategies.values()) {
            if (cfg.getBoolean("strategy." + s.id() + ".enabled")) {
                out.add(s);
            }
        }
        return out;
    }

    public double weightOf(SignalStrategy s, StrategyConfig cfg) {
        return cfg.getDouble("strategy." + s.id() + ".weight");
    }
}

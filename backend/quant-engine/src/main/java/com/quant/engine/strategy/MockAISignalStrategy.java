package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;

import java.util.List;

/**
 * AI 通道 Mock 实现：返回固定分数，用于联调融合逻辑与看板展示。
 * 真实 DeepSeek 接线后替换为 DeepSeekAISignalStrategy（M2 不做）。
 */
public class MockAISignalStrategy implements AISignalStrategy {
    public static final String ID = "ai_mock";

    private final double fixedScore;

    public MockAISignalStrategy() {
        this(70.0);
    }

    public MockAISignalStrategy(double fixedScore) {
        this.fixedScore = fixedScore;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AISignal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        String fundInfo = fund.isPresent()
                ? String.format("ROE %s%%", fund.latest().orElseThrow().roe())
                : "无可见财报";
        return new AISignal(fixedScore,
                String.format("Mock：%s（%s），技术面 %d 根 K 线已阅", symbol, fundInfo, bars.size()),
                "Mock 未做真实风险评估");
    }
}

package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;

import java.util.List;

/**
 * AI 通道接口：大模型给股票打分（0-100）+ 自然语言理由 + 风险点。
 * 与 SignalStrategy 的区别：AI 是独立通道，参与融合层的双通道打分，
 * 而不是第三层技术面策略之一。
 *
 * 真实实现（DeepSeek API）等 Ma 提供 API Key 后再接线；
 * 代码里绝不硬编码 Key，Key 从环境变量 DEEPSEEK_API_KEY 读取（到时）。
 */
public interface AISignalStrategy {
    String id();

    AISignal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg);

    /** AI 通道输出：分数 + 理由 + 风险点（比技术面 Signal 多一个风险点字段）。 */
    record AISignal(double score, String reason, String riskPoints) {
        public AISignal {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("ai_score 必须在 0-100 之间: " + score);
            }
        }
    }
}

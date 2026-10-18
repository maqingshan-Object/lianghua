package com.quant.engine.strategy;

/**
 * AI 打分失败异常（unchecked）。
 * <p>
 * 触发场景：未配置 Key、网络超时、HTTP 错误、返回非 JSON。
 * SelectorEngine 捕获后自动降级为 ENGINE_ONLY，不阻塞选股。
 * <p>
 * 注意：异常信息里绝不携带 API Key 的任何部分。
 */
public class AiScoringException extends RuntimeException {
    public AiScoringException(String message) {
        super(message);
    }

    public AiScoringException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.quant.engine.model;

/** 过滤器结果：通过/拦截 + 原因（拦截原因会进日志与报告）。 */
public record FilterResult(boolean passed, String reason) {
    public static FilterResult pass(String reason) {
        return new FilterResult(true, reason);
    }

    public static FilterResult reject(String reason) {
        return new FilterResult(false, reason);
    }
}

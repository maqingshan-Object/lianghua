package com.quant.engine.model;

import java.time.LocalDate;
import java.util.Optional;

/**
 * PIT 快照：某只股票在 asOf 日期"当时可见"的最新一期财报。
 * empty 表示当时没有任何已公告财报（次新股常见），调用方必须按"数据缺失"处理，
 * 不得回退使用未来才公告的数据。
 */
public record FundamentalSnapshot(
        String symbol,
        LocalDate asOf,
        Optional<Fundamental> latest
) {
    public static FundamentalSnapshot empty(String symbol, LocalDate asOf) {
        return new FundamentalSnapshot(symbol, asOf, Optional.empty());
    }

    public boolean isPresent() {
        return latest.isPresent();
    }
}

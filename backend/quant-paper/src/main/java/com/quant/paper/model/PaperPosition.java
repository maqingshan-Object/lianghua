package com.quant.paper.model;

import java.time.LocalDate;

/** 模拟持仓（paper_positions）。costPrice 为含买入佣金的均价快照（实现盈亏按此口径）。 */
public record PaperPosition(
        String symbol,
        String name,
        long qty,
        double costPrice,
        LocalDate buyDate,
        String buyReason,
        int versionId
) {}

package com.quant.paper.model;

import java.time.LocalDate;

/**
 * 模拟成交记录（paper_trades）。
 * side: BUY / SELL。realizedPnl 仅卖出时有值（成交净额 - 成本价*数量）。
 */
public record PaperTrade(
        long id,
        LocalDate signalDate,
        LocalDate tradeDate,
        String symbol,
        String side,
        double price,
        long qty,
        double commission,
        double stampTax,
        String reasonJson,
        int versionId,
        Double realizedPnl
) {}

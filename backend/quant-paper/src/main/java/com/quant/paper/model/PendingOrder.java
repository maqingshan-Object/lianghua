package com.quant.paper.model;

import java.time.LocalDate;

/**
 * 待成交挂单（paper_pending_orders）。
 * 信号 T 日收盘产生，T+1 开盘成交——挂单是"延迟执行"的载体。
 * status: PENDING / FILLED / CANCELLED。
 */
public record PendingOrder(
        long id,
        String symbol,
        String name,
        String side,
        long qty,
        double refPrice,
        LocalDate signalDate,
        String reasonJson,
        int versionId,
        String status,
        int retries
) {
    public boolean isBuy() {
        return "BUY".equals(side);
    }
}

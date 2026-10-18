package com.quant.engine.model;

import java.time.LocalDate;

/** stocks 表一行：股票基础信息。 */
public record Stock(
        String symbol,      // 如 600519.SH
        String name,
        String market,      // SH / SZ / BJ
        String industry,
        LocalDate listDate, // 上市日期
        boolean st,
        boolean suspended
) {}

package com.quant.engine.model;

import java.time.LocalDate;

/**
 * daily_bars 表一行：日线行情。
 * 注意：open/high/low/close 均为前复权价（M1 采集时已处理），指标计算直接用。
 */
public record Bar(
        String symbol,
        LocalDate tradeDate,
        double open,
        double high,
        double low,
        double close,
        double volume,   // 手
        double amount,   // 元
        Double pctChg,   // 涨跌幅 %，可空
        Double turnover, // 换手率 %，可空
        Double peTtm,    // AKShare 下为 NULL
        Double pbMrq,    // AKShare 下为 NULL
        boolean suspended
) {}

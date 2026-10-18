package com.quant.engine.model;

import java.time.LocalDate;

/**
 * fundamentals 表一行。
 * PIT（Point-in-Time）铁律：任何时候只能用 announceDate <= 当前日期的数据，
 * 绝不能用 reportDate 判断"当时是否可见"，否则引入前视偏差。
 */
public record Fundamental(
        String symbol,
        LocalDate reportDate,   // 报告期
        LocalDate announceDate, // 公告日期（可空；为空视为不可用）
        Double roe,             // 净资产收益率 %
        Double revenueYoy,      // 营收同比 %
        Double netprofitYoy,    // 净利润同比 %
        Double debtRatio,       // 资产负债率 %
        Double ocf              // 经营现金流净额（元）
) {
    /** 该行在 asOf 日期是否"当时可见"。 */
    public boolean visibleAt(LocalDate asOf) {
        return announceDate != null && !announceDate.isAfter(asOf);
    }
}

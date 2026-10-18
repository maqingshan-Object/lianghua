package com.quant.engine.filter;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Stock;

import java.time.LocalDate;
import java.util.List;

/** 股票过滤器接口：L1 基础过滤 / L2 基本面过滤都实现它。 */
public interface StockFilter {
    /** 通过返回 pass，拦截返回 reject（原因会进日志）。 */
    FilterResult filter(Stock stock, List<Bar> bars, FundamentalSnapshot fund,
                        StrategyConfig cfg, LocalDate asOf, MarketDataService mds);
}

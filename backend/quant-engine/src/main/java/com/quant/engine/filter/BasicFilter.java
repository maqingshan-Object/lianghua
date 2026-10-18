package com.quant.engine.filter;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Stock;

import java.time.LocalDate;
import java.util.List;

/**
 * 第一层·基础过滤：剔除 ST、停牌、上市不满 N 年、日成交额过低。
 * 只用"当时"数据：bars 取 asOf 之前窗口，成交额取最近一根 Bar。
 */
public class BasicFilter implements StockFilter {

    @Override
    public FilterResult filter(Stock stock, List<Bar> bars, FundamentalSnapshot fund,
                               StrategyConfig cfg, LocalDate asOf, MarketDataService mds) {
        if (stock.st()) {
            return FilterResult.reject("ST 股");
        }
        if (stock.suspended()) {
            return FilterResult.reject("停牌中");
        }
        double minYears = cfg.getDouble(StrategyConfig.FILTER_MIN_LIST_YEARS);
        if (!StrategyConfig.listedLongEnough(stock.listDate(), asOf, minYears)) {
            return FilterResult.reject("上市不满 " + minYears + " 年（上市日 " + stock.listDate() + ")");
        }
        if (bars.isEmpty()) {
            return FilterResult.reject("无行情数据");
        }
        Bar last = bars.get(bars.size() - 1);
        if (last.suspended()) {
            return FilterResult.reject("最近交易日停牌");
        }
        double minAmount = cfg.getDouble(StrategyConfig.FILTER_MIN_AMOUNT);
        if (last.amount() < minAmount) {
            return FilterResult.reject(String.format("日成交额 %.0f 元低于下限 %.0f 元", last.amount(), minAmount));
        }
        return FilterResult.pass("通过基础过滤");
    }
}

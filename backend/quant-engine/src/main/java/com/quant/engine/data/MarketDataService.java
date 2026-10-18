package com.quant.engine.data;

import com.quant.engine.model.Bar;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Stock;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情/基本面读取门面：带行业估值分布缓存（一次选股运行内复用）。
 * 所有"当时"语义都以 asOf 日期为准，PIT 由 QuantDb 的 SQL 保证。
 */
public class MarketDataService {
    private final QuantDb db;
    private List<Stock> stocksCache;

    // (industry|date) -> 排序后的 PE / PB 列表
    private final Map<String, List<Double>> peCache = new HashMap<>();
    private final Map<String, List<Double>> pbCache = new HashMap<>();

    public MarketDataService(QuantDb db) {
        this.db = db;
    }

    public List<Stock> stocks() throws SQLException {
        if (stocksCache == null) {
            stocksCache = db.loadStocks();
        }
        return stocksCache;
    }

    public List<Bar> bars(String symbol, LocalDate asOf, int window) throws SQLException {
        return db.loadBars(symbol, asOf, window);
    }

    public FundamentalSnapshot fundamental(String symbol, LocalDate asOf) throws SQLException {
        Fundamental f = db.loadLatestVisibleFundamental(symbol, asOf);
        if (f == null) {
            return FundamentalSnapshot.empty(symbol, asOf);
        }
        return new FundamentalSnapshot(symbol, asOf, java.util.Optional.of(f));
    }

    /**
     * PE 在行业内的分位数（0-1，越小越便宜）。
     * 返回 empty 表示当时行业内无可用估值数据（AKShare 下常见），调用方应跳过估值判断。
     */
    public java.util.OptionalDouble industryPePercentile(String industry, double pe, LocalDate asOf)
            throws SQLException {
        List<Double> dist = peDist(industry, asOf);
        return percentile(dist, pe);
    }

    public java.util.OptionalDouble industryPbPercentile(String industry, double pb, LocalDate asOf)
            throws SQLException {
        List<Double> dist = pbDist(industry, asOf);
        return percentile(dist, pb);
    }

    private List<Double> peDist(String industry, LocalDate asOf) throws SQLException {
        String key = industry + "|" + asOf;
        return peCache.computeIfAbsent(key, k -> {
            try {
                List<Double> l = new ArrayList<>(db.loadIndustryPes(industry, asOf));
                Collections.sort(l);
                return l;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<Double> pbDist(String industry, LocalDate asOf) throws SQLException {
        String key = industry + "|" + asOf;
        return pbCache.computeIfAbsent(key, k -> {
            try {
                List<Double> l = new ArrayList<>(db.loadIndustryPbs(industry, asOf));
                Collections.sort(l);
                return l;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 分位数 = 分布中 <= v 的比例。分布为空时返回 empty。 */
    private static java.util.OptionalDouble percentile(List<Double> sorted, double v) {
        if (sorted.isEmpty()) {
            return java.util.OptionalDouble.empty();
        }
        int le = 0;
        for (double d : sorted) {
            if (d <= v) {
                le++;
            } else {
                break;
            }
        }
        return java.util.OptionalDouble.of((double) le / sorted.size());
    }
}

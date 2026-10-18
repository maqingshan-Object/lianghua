package com.quant.backtest.engine;

import com.quant.engine.data.QuantDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 沪深 300（000300.SH）基准曲线。
 *
 * 口径：按策略初始资金归一化（benchNav = initial * close/close首日），
 * 与策略净值曲线同图对比；超额收益 = 策略总收益率 - 基准总收益率。
 * 按策略的交易日对齐：基准缺失某交易日时顺延上一可用收盘价。
 * 基准无数据时 available=false，报告明确标注"基准数据缺失，未做对比"。
 */
public class BenchmarkService {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);
    public static final String BENCHMARK_SYMBOL = "000300.SH";

    public record BenchmarkCurve(boolean available, List<Double> values, double totalReturnPct) {
    }

    public BenchmarkCurve build(QuantDb qdb, List<LocalDate> navDates, double initialCapital)
            throws SQLException {
        if (navDates.isEmpty()) {
            return new BenchmarkCurve(false, List.of(), Double.NaN);
        }
        TreeMap<LocalDate, Double> closes = new TreeMap<>();
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT trade_date, close FROM daily_bars WHERE symbol=? " +
                "AND trade_date BETWEEN ? AND ? ORDER BY trade_date")) {
            ps.setString(1, BENCHMARK_SYMBOL);
            ps.setString(2, navDates.get(0).toString());
            ps.setString(3, navDates.get(navDates.size() - 1).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    closes.put(LocalDate.parse(rs.getString("trade_date")), rs.getDouble("close"));
                }
            }
        }
        if (closes.isEmpty()) {
            log.warn("基准 {} 在区间内无行情数据，不做基准对比", BENCHMARK_SYMBOL);
            return new BenchmarkCurve(false, List.of(), Double.NaN);
        }
        double first = closes.firstEntry().getValue();
        List<Double> values = new ArrayList<>();
        double lastClose = first;
        for (LocalDate d : navDates) {
            Map.Entry<LocalDate, Double> e = closes.floorEntry(d);
            if (e != null) {
                lastClose = e.getValue();
            }
            values.add(first > 0 ? initialCapital * lastClose / first : Double.NaN);
        }
        double tr = first > 0 ? (lastClose - first) / first * 100.0 : Double.NaN;
        return new BenchmarkCurve(true, values, tr);
    }
}

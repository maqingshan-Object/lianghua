package com.quant.backtest.engine;

import com.quant.engine.data.QuantDb;
import com.quant.paper.model.PaperTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绩效指标计算。所有输入均来自回放产生的真实记录：
 * paper_daily_nav（净值曲线）、paper_trades（逐笔成交）、daily_bars（持有期收益）。
 *
 * 口径声明（报告假设区会逐条列出）：
 * - 年化按 250 个交易日/年；
 * - 夏普比率无风险利率取 0；
 * - 胜率 = 盈利卖出笔数 / 有盈亏卖出笔数（零盈亏不计入）；
 * - 盈亏比 = 平均盈利 / 平均亏损；
 * - 平均持仓天数按股数加权 FIFO 匹配买卖计算。
 */
public class MetricsCalculator {
    private static final Logger log = LoggerFactory.getLogger(MetricsCalculator.class);
    public static final double TRADING_DAYS_PER_YEAR = 250.0;

    public record NavSeries(List<LocalDate> dates, List<Double> navs) {
    }

    public record WinStats(int counted, int wins, double winRatePct, double profitLossRatio) {
    }

    public record Drawdown(double pct, LocalDate start, LocalDate end) {
    }

    // ---------------- 数据读取 ----------------

    public static NavSeries loadNavSeries(QuantDb qdb, LocalDate from, LocalDate to) throws SQLException {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> navs = new ArrayList<>();
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT trade_date, total_asset FROM paper_daily_nav " +
                "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dates.add(LocalDate.parse(rs.getString("trade_date")));
                    navs.add(rs.getDouble("total_asset"));
                }
            }
        }
        return new NavSeries(dates, navs);
    }

    public static List<PaperTrade> loadTrades(QuantDb qdb, LocalDate from, LocalDate to) throws SQLException {
        List<PaperTrade> out = new ArrayList<>();
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT id, signal_date, trade_date, symbol, side, price, qty, commission, stamp_tax, " +
                "reason_json, version_id, realized_pnl FROM paper_trades " +
                "WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, id")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double pnl = rs.getDouble("realized_pnl");
                    boolean pnlNull = rs.wasNull(); // 必须紧跟 getDouble
                    out.add(new PaperTrade(rs.getLong("id"),
                            LocalDate.parse(rs.getString("signal_date")),
                            LocalDate.parse(rs.getString("trade_date")),
                            rs.getString("symbol"), rs.getString("side"),
                            rs.getDouble("price"), rs.getLong("qty"),
                            rs.getDouble("commission"), rs.getDouble("stamp_tax"),
                            rs.getString("reason_json"), rs.getInt("version_id"),
                            pnlNull ? null : pnl));
                }
            }
        }
        return out;
    }

    /**
     * 作废挂单数。注意 paper_pending_orders.created_at 是挂单创建时的墙钟时间，
     * 在回测专用库（回放前已 resetAll）中所有挂单都属于本次回放，直接全表计数。
     */
    public static int loadCancelledOrderCount(QuantDb qdb) throws SQLException {
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM paper_pending_orders WHERE status='CANCELLED'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---------------- 基础指标 ----------------

    public static double totalReturnPct(double initial, double last) {
        return initial > 0 ? (last - initial) / initial * 100.0 : Double.NaN;
    }

    public static double annualizedPct(double totalReturnPct, int tradingDays) {
        if (tradingDays <= 0 || Double.isNaN(totalReturnPct)) {
            return Double.NaN;
        }
        double total = totalReturnPct / 100.0;
        return (Math.pow(1 + total, TRADING_DAYS_PER_YEAR / tradingDays) - 1) * 100.0;
    }

    public static Drawdown maxDrawdown(List<Double> navs, List<LocalDate> dates) {
        double peak = Double.NEGATIVE_INFINITY;
        double maxDd = 0;
        LocalDate start = null, end = null, peakDate = null;
        for (int i = 0; i < navs.size(); i++) {
            double v = navs.get(i);
            if (v > peak) {
                peak = v;
                peakDate = dates.get(i);
            }
            double dd = peak > 0 ? (v - peak) / peak * 100.0 : 0;
            if (dd < maxDd) {
                maxDd = dd;
                start = peakDate;
                end = dates.get(i);
            }
        }
        return new Drawdown(maxDd, start, end);
    }

    /** 日收益率 → 年化夏普比率（无风险利率取 0）。无波动时记 0。 */
    public static double sharpe(List<Double> navs) {
        if (navs.size() < 2) {
            return Double.NaN;
        }
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < navs.size(); i++) {
            double prev = navs.get(i - 1);
            if (prev > 0) {
                rets.add(navs.get(i) / prev - 1);
            }
        }
        if (rets.size() < 2) {
            return Double.NaN;
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (rets.size() - 1);
        double std = Math.sqrt(var);
        if (std == 0) {
            return 0;
        }
        return mean / std * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    public static WinStats winStats(List<PaperTrade> sells) {
        int wins = 0, losses = 0;
        double winSum = 0, lossSum = 0;
        for (PaperTrade t : sells) {
            Double p = t.realizedPnl();
            if (p == null) {
                continue;
            }
            if (p > 0) {
                wins++;
                winSum += p;
            } else if (p < 0) {
                losses++;
                lossSum += -p;
            }
        }
        int n = wins + losses;
        double wr = n == 0 ? Double.NaN : (double) wins / n * 100.0;
        double plr = (wins > 0 && losses > 0) ? (winSum / wins) / (lossSum / losses) : Double.NaN;
        return new WinStats(n, wins, wr, plr);
    }

    /** FIFO 匹配买卖，按股数加权计算平均持仓天数。 */
    public static double avgHoldingDays(List<PaperTrade> trades) {
        Map<String, Deque<double[]>> lots = new HashMap<>(); // symbol -> [买入epochDay, 剩余股数]
        double qtyDays = 0;
        double totalQty = 0;
        List<PaperTrade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(PaperTrade::tradeDate).thenComparing(PaperTrade::id));
        for (PaperTrade t : sorted) {
            Deque<double[]> q = lots.computeIfAbsent(t.symbol(), k -> new ArrayDeque<>());
            if ("BUY".equals(t.side())) {
                q.addLast(new double[]{t.tradeDate().toEpochDay(), t.qty()});
            } else {
                long remaining = t.qty();
                while (remaining > 0 && !q.isEmpty()) {
                    double[] lot = q.peekFirst();
                    long take = Math.min(remaining, (long) lot[1]);
                    long days = t.tradeDate().toEpochDay() - (long) lot[0];
                    qtyDays += take * days;
                    totalQty += take;
                    lot[1] -= take;
                    remaining -= take;
                    if (lot[1] <= 0) {
                        q.pollFirst();
                    }
                }
                if (remaining > 0) {
                    log.warn("卖出 {} {}股 无对应买入批次（{}股未匹配），该部分不计入持仓天数",
                            t.symbol(), t.qty(), remaining);
                }
            }
        }
        return totalQty > 0 ? qtyDays / totalQty : Double.NaN;
    }

    // ---------------- 持有期收益分布 ----------------

    /**
     * 每笔买入成交后第 h 个交易日的收益率（相对实际成交价）。
     * horizons 如 {5, 10, 20}。未来 Bar 不足时该笔跳过对应期限。
     */
    public static Map<Integer, List<Double>> holdingPeriodReturns(
            QuantDb qdb, List<PaperTrade> buys, int[] horizons) throws SQLException {
        Map<Integer, List<Double>> out = new LinkedHashMap<>();
        for (int h : horizons) {
            out.put(h, new ArrayList<>());
        }
        Map<String, List<LocalDate>> calendars = new HashMap<>();
        Map<String, List<Double>> closes = new HashMap<>();
        for (PaperTrade b : buys) {
            if (!"BUY".equals(b.side())) {
                continue;
            }
            if (!calendars.containsKey(b.symbol())) {
                List<LocalDate> cal = new ArrayList<>();
                List<Double> cl = new ArrayList<>();
                try (PreparedStatement ps = qdb.connection().prepareStatement(
                        "SELECT trade_date, close FROM daily_bars WHERE symbol=? ORDER BY trade_date")) {
                    ps.setString(1, b.symbol());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            cal.add(LocalDate.parse(rs.getString("trade_date")));
                            cl.add(rs.getDouble("close"));
                        }
                    }
                }
                calendars.put(b.symbol(), cal);
                closes.put(b.symbol(), cl);
            }
        }
        for (PaperTrade b : buys) {
            if (!"BUY".equals(b.side()) || b.price() <= 0) {
                continue;
            }
            List<LocalDate> cal = calendars.get(b.symbol());
            List<Double> cl = closes.get(b.symbol());
            int idx = Collections.binarySearch(cal, b.tradeDate());
            if (idx < 0) {
                idx = -idx - 2; // 买入日无行情时向前取最近一根
            }
            if (idx < 0) {
                continue;
            }
            for (int h : horizons) {
                int j = idx + h;
                if (j < cl.size()) {
                    out.get(h).add((cl.get(j) - b.price()) / b.price() * 100.0);
                }
            }
        }
        return out;
    }

    // ---------------- 样本内 / 外 ----------------

    /**
     * 按时间 8:2 划分样本内/外。样本外天然不参与调参——本回测全程使用
     * 锁定版本，未做任何参数寻优，此处仅做指标切片展示。
     */
    public static int[] inOutSplit(int n) {
        int in = (int) (n * 0.8);
        return new int[]{in, n - in};
    }
}

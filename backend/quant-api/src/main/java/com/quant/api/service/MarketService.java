package com.quant.api.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.engine.data.QuantDb;

/** 市场概况：指数涨跌 / 涨跌家数 / 成交额 / 热门行业（全部来自 daily_bars，只读）。 */
@Service
public class MarketService extends BaseService {

    private static final String[][] INDICES = {
            {"上证指数", "000001.SH"},
            {"深证成指", "399001.SZ"},
            {"创业板指", "399006.SZ"},
            {"沪深300", "000300.SH"},
    };
    private static final String INDEX_LIST = "'000001.SH','399001.SZ','399006.SZ','000300.SH'";

    public MarketService(ApiProperties props) {
        super(props);
    }

    public Dtos.MarketOverview overview() throws SQLException {
        return withDb(db -> {
            LocalDate date = latestDate(db);
            if (date == null) {
                throw new IllegalStateException("库中暂无行情数据（daily_bars 为空），请先运行 M1 采集");
            }
            List<Dtos.IndexInfo> indices = new ArrayList<>();
            for (String[] idx : INDICES) {
                Double chg = queryChangePct(db, idx[1], date);
                if (chg != null) {
                    indices.add(new Dtos.IndexInfo(idx[0], idx[1], round2(chg)));
                }
            }

            int advance = 0, decline = 0;
            Double amount = null;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT SUM(CASE WHEN b.pct_chg > 0 THEN 1 ELSE 0 END), " +
                    "       SUM(CASE WHEN b.pct_chg < 0 THEN 1 ELSE 0 END), " +
                    "       SUM(b.amount) " +
                    "FROM daily_bars b JOIN stocks s ON s.symbol = b.symbol " +
                    "WHERE b.trade_date = ? AND b.pct_chg IS NOT NULL " +
                    "  AND b.symbol NOT IN (" + INDEX_LIST + ")")) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        advance = rs.getInt(1);
                        decline = rs.getInt(2);
                        double a = rs.getDouble(3);
                        amount = rs.wasNull() ? null : round2(a);
                    }
                }
            }

            List<Dtos.HotIndustry> hot = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT s.industry, AVG(b.pct_chg), COUNT(*) " +
                    "FROM daily_bars b JOIN stocks s ON s.symbol = b.symbol " +
                    "WHERE b.trade_date = ? AND b.pct_chg IS NOT NULL " +
                    "  AND s.industry IS NOT NULL AND s.industry <> '' " +
                    "  AND b.symbol NOT IN (" + INDEX_LIST + ") " +
                    "GROUP BY s.industry ORDER BY AVG(b.pct_chg) DESC LIMIT 5")) {
                ps.setString(1, date.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hot.add(new Dtos.HotIndustry(rs.getString(1), round2(rs.getDouble(2)), rs.getInt(3)));
                    }
                }
            }
            return new Dtos.MarketOverview(date, indices, advance, decline, amount, hot);
        });
    }

    private LocalDate latestDate(QuantDb db) throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(trade_date) FROM daily_bars")) {
            if (rs.next()) {
                String s = rs.getString(1);
                return s == null ? null : LocalDate.parse(s);
            }
            return null;
        }
    }

    private Double queryChangePct(QuantDb db, String symbol, LocalDate date) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT pct_chg FROM daily_bars WHERE symbol = ? AND trade_date = ?")) {
            ps.setString(1, symbol);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double v = rs.getDouble(1);
                    return rs.wasNull() ? null : v;
                }
                return null;
            }
        }
    }
}

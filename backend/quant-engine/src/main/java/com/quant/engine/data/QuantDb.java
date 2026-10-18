package com.quant.engine.data;

import com.quant.engine.model.Bar;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 访问层（只读 M1 的四表 + 读写本里程碑新增的配置/选股表）。
 * M2 不写 M1 的表，只读。
 */
public class QuantDb implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(QuantDb.class);

    private final Connection conn;

    public QuantDb(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.conn.setAutoCommit(true);
        initM2Schema();
    }

    /** M2 新增表：配置中心 + 选股运行记录。 */
    private void initM2Schema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS strategy_config (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS strategy_config_version (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        version_no INTEGER NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        effective_date TEXT NOT NULL,
                        changes TEXT,
                        snapshot TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS select_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_date TEXT NOT NULL,
                        version_id INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        top_n INTEGER NOT NULL,
                        universe INTEGER NOT NULL,
                        passed_l1 INTEGER NOT NULL,
                        passed_l2 INTEGER NOT NULL,
                        candidates INTEGER NOT NULL,
                        created_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS select_candidates (
                        run_id INTEGER NOT NULL,
                        rank_no INTEGER NOT NULL,
                        symbol TEXT NOT NULL,
                        name TEXT,
                        industry TEXT,
                        final_score REAL NOT NULL,
                        ai_score REAL,
                        engine_score REAL,
                        factor_json TEXT,
                        reason TEXT,
                        buy_low REAL,
                        buy_high REAL,
                        stop_loss REAL,
                        target REAL,
                        PRIMARY KEY (run_id, rank_no)
                    )""");
        }
    }

    // ---------------- M1 表：只读 ----------------

    public List<Stock> loadStocks() throws SQLException {
        String sql = "SELECT symbol, name, market, industry, list_date, is_st, suspended FROM stocks";
        List<Stock> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new Stock(
                        rs.getString("symbol"),
                        rs.getString("name"),
                        rs.getString("market"),
                        rs.getString("industry"),
                        parseDate(rs.getString("list_date")),
                        rs.getInt("is_st") == 1,
                        rs.getInt("suspended") == 1));
            }
        }
        log.info("载入股票 {} 只", out.size());
        return out;
    }

    /** 取某只股票在 asOf（含）之前的最近 N 根日线（时间升序）。 */
    public List<Bar> loadBars(String symbol, LocalDate asOf, int limit) throws SQLException {
        String sql = """
                SELECT symbol, trade_date, open, high, low, close, volume, amount,
                       pct_chg, turnover, pe_ttm, pb_mrq, suspended
                FROM daily_bars
                WHERE symbol = ? AND trade_date <= ?
                ORDER BY trade_date DESC LIMIT ?""";
        List<Bar> desc = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, asOf.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    desc.add(new Bar(
                            rs.getString("symbol"),
                            LocalDate.parse(rs.getString("trade_date")),
                            rs.getDouble("open"), rs.getDouble("high"),
                            rs.getDouble("low"), rs.getDouble("close"),
                            rs.getDouble("volume"), rs.getDouble("amount"),
                            getDoubleOrNull(rs, "pct_chg"),
                            getDoubleOrNull(rs, "turnover"),
                            getDoubleOrNull(rs, "pe_ttm"),
                            getDoubleOrNull(rs, "pb_mrq"),
                            rs.getInt("suspended") == 1));
                }
            }
        }
        // 反转为时间升序
        List<Bar> asc = new ArrayList<>(desc.size());
        for (int i = desc.size() - 1; i >= 0; i--) {
            asc.add(desc.get(i));
        }
        return asc;
    }

    /**
     * PIT 查询：某只股票在 asOf 当时可见的最新一期财报。
     * 只认 announce_date <= asOf，announce_date 为空的行视为不可见（保守）。
     */
    public Fundamental loadLatestVisibleFundamental(String symbol, LocalDate asOf) throws SQLException {
        String sql = """
                SELECT symbol, report_date, announce_date, roe, revenue_yoy,
                       netprofit_yoy, debt_ratio, ocf
                FROM fundamentals
                WHERE symbol = ?
                  AND announce_date IS NOT NULL
                  AND announce_date <= ?
                ORDER BY announce_date DESC, report_date DESC
                LIMIT 1""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, asOf.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Fundamental(
                        rs.getString("symbol"),
                        parseDate(rs.getString("report_date")),
                        parseDate(rs.getString("announce_date")),
                        getDoubleOrNull(rs, "roe"),
                        getDoubleOrNull(rs, "revenue_yoy"),
                        getDoubleOrNull(rs, "netprofit_yoy"),
                        getDoubleOrNull(rs, "debt_ratio"),
                        getDoubleOrNull(rs, "ocf"));
            }
        }
    }

    /** 某行业在某日有 PE 数据的股票 PE 列表（用于行业分位；AKShare 下可能为空）。 */
    public List<Double> loadIndustryPes(String industry, LocalDate asOf) throws SQLException {
        String sql = """
                SELECT b.pe_ttm FROM daily_bars b
                JOIN stocks s ON s.symbol = b.symbol
                WHERE s.industry = ? AND b.trade_date = ?
                  AND b.pe_ttm IS NOT NULL AND b.pe_ttm > 0""";
        return loadDoubles(sql, industry, asOf.toString());
    }

    public List<Double> loadIndustryPbs(String industry, LocalDate asOf) throws SQLException {
        String sql = """
                SELECT b.pb_mrq FROM daily_bars b
                JOIN stocks s ON s.symbol = b.symbol
                WHERE s.industry = ? AND b.trade_date = ?
                  AND b.pb_mrq IS NOT NULL AND b.pb_mrq > 0""";
        return loadDoubles(sql, industry, asOf.toString());
    }

    private List<Double> loadDoubles(String sql, String p1, String p2) throws SQLException {
        List<Double> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1);
            ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getDouble(1));
                }
            }
        }
        return out;
    }

    // ---------------- M2 表：读写 ----------------

    public Connection connection() {
        return conn;
    }

    private static LocalDate parseDate(String s) {
        return (s == null || s.isBlank()) ? null : LocalDate.parse(s.substring(0, 10));
    }

    private static Double getDoubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}

package com.quant.paper.data;

import com.quant.engine.data.QuantDb;
import com.quant.paper.model.PaperAccount;
import com.quant.paper.model.PaperPosition;
import com.quant.paper.model.PaperTrade;
import com.quant.paper.model.PendingOrder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟交易表访问层。与 QuantDb 共享同一 SQLite 连接（同一库文件）。
 * M3 不写 M1 的四表，也不写 M2 的配置/选股表，只读写 paper_* 表。
 */
public class PaperDb {
    private final QuantDb qdb;

    public PaperDb(QuantDb qdb) throws SQLException {
        this.qdb = qdb;
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement st = qdb.connection().createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS paper_account (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        cash REAL NOT NULL,
                        total_asset REAL NOT NULL,
                        updated_date TEXT,
                        version_id INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS paper_positions (
                        symbol TEXT PRIMARY KEY,
                        name TEXT,
                        qty INTEGER NOT NULL,
                        cost_price REAL NOT NULL,
                        buy_date TEXT NOT NULL,
                        buy_reason TEXT NOT NULL,
                        version_id INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS paper_trades (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        signal_date TEXT NOT NULL,
                        trade_date TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        side TEXT NOT NULL,
                        price REAL NOT NULL,
                        qty INTEGER NOT NULL,
                        commission REAL NOT NULL,
                        stamp_tax REAL NOT NULL,
                        reason_json TEXT NOT NULL,
                        version_id INTEGER NOT NULL,
                        realized_pnl REAL,
                        created_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS paper_pending_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        symbol TEXT NOT NULL,
                        name TEXT,
                        side TEXT NOT NULL,
                        qty INTEGER NOT NULL,
                        ref_price REAL NOT NULL,
                        signal_date TEXT NOT NULL,
                        reason_json TEXT NOT NULL,
                        version_id INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        retries INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS paper_daily_nav (
                        trade_date TEXT PRIMARY KEY,
                        total_asset REAL NOT NULL,
                        cash REAL NOT NULL,
                        position_value REAL NOT NULL,
                        daily_pnl REAL NOT NULL,
                        version_id INTEGER NOT NULL
                    )""");
        }
    }

    /** 账户不存在时用初始资金建行；已存在则不动（--reset 才重建）。 */
    public void ensureAccount(double initialCapital, int versionId) throws SQLException {
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM paper_account WHERE id = 1")) {
            rs.next();
            if (rs.getInt(1) == 0) {
                try (PreparedStatement ps = qdb.connection().prepareStatement(
                        "INSERT INTO paper_account(id, cash, total_asset, updated_date, version_id) VALUES(1,?,?,?,?)")) {
                    ps.setDouble(1, initialCapital);
                    ps.setDouble(2, initialCapital);
                    ps.setString(3, null);
                    ps.setInt(4, versionId);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** 危险操作：清空所有 paper_* 表并重建账户。仅 CLI --reset 调用。 */
    public void resetAll(double initialCapital, int versionId) throws SQLException {
        try (Statement st = qdb.connection().createStatement()) {
            st.execute("DELETE FROM paper_pending_orders");
            st.execute("DELETE FROM paper_trades");
            st.execute("DELETE FROM paper_positions");
            st.execute("DELETE FROM paper_daily_nav");
            st.execute("DELETE FROM paper_account");
        }
        ensureAccount(initialCapital, versionId);
    }

    public PaperAccount loadAccount() throws SQLException {
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT cash, total_asset, updated_date, version_id FROM paper_account WHERE id = 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("模拟账户未初始化，请先运行（自动初始化）或 --reset");
            }
            String d = rs.getString("updated_date");
            return new PaperAccount(rs.getDouble("cash"), rs.getDouble("total_asset"),
                    d == null ? null : LocalDate.parse(d), rs.getInt("version_id"));
        }
    }

    public void saveAccount(double cash, double totalAsset, LocalDate date, int versionId) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "UPDATE paper_account SET cash=?, total_asset=?, updated_date=?, version_id=? WHERE id=1")) {
            ps.setDouble(1, cash);
            ps.setDouble(2, totalAsset);
            ps.setString(3, date == null ? null : date.toString());
            ps.setInt(4, versionId);
            ps.executeUpdate();
        }
    }

    public List<PaperPosition> loadPositions() throws SQLException {
        List<PaperPosition> out = new ArrayList<>();
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT symbol, name, qty, cost_price, buy_date, buy_reason, version_id FROM paper_positions ORDER BY symbol")) {
            while (rs.next()) {
                out.add(new PaperPosition(rs.getString("symbol"), rs.getString("name"),
                        rs.getLong("qty"), rs.getDouble("cost_price"),
                        LocalDate.parse(rs.getString("buy_date")),
                        rs.getString("buy_reason"), rs.getInt("version_id")));
            }
        }
        return out;
    }

    public void upsertPosition(PaperPosition p) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "INSERT INTO paper_positions(symbol, name, qty, cost_price, buy_date, buy_reason, version_id) " +
                "VALUES(?,?,?,?,?,?,?) " +
                "ON CONFLICT(symbol) DO UPDATE SET qty=excluded.qty, cost_price=excluded.cost_price, " +
                "buy_date=excluded.buy_date, buy_reason=excluded.buy_reason, version_id=excluded.version_id")) {
            ps.setString(1, p.symbol());
            ps.setString(2, p.name());
            ps.setLong(3, p.qty());
            ps.setDouble(4, p.costPrice());
            ps.setString(5, p.buyDate().toString());
            ps.setString(6, p.buyReason());
            ps.setInt(7, p.versionId());
            ps.executeUpdate();
        }
    }

    public void deletePosition(String symbol) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "DELETE FROM paper_positions WHERE symbol=?")) {
            ps.setString(1, symbol);
            ps.executeUpdate();
        }
    }

    public List<PendingOrder> loadPendingOrders() throws SQLException {
        List<PendingOrder> out = new ArrayList<>();
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, symbol, name, side, qty, ref_price, signal_date, reason_json, version_id, status, retries " +
                     "FROM paper_pending_orders WHERE status='PENDING' ORDER BY id")) {
            while (rs.next()) {
                out.add(new PendingOrder(rs.getLong("id"), rs.getString("symbol"), rs.getString("name"),
                        rs.getString("side"), rs.getLong("qty"), rs.getDouble("ref_price"),
                        LocalDate.parse(rs.getString("signal_date")), rs.getString("reason_json"),
                        rs.getInt("version_id"), rs.getString("status"), rs.getInt("retries")));
            }
        }
        return out;
    }

    public long insertPendingOrder(String symbol, String name, String side, long qty, double refPrice,
                                   LocalDate signalDate, String reasonJson, int versionId) throws SQLException {
        if (reasonJson == null || reasonJson.isBlank()) {
            throw new IllegalArgumentException("挂单必须携带理由：没有理由的交易不允许发生");
        }
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "INSERT INTO paper_pending_orders(symbol, name, side, qty, ref_price, signal_date, reason_json, version_id, status, retries, created_at) " +
                "VALUES(?,?,?,?,?,?,?,?, 'PENDING', 0, datetime('now'))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, symbol);
            ps.setString(2, name);
            ps.setString(3, side);
            ps.setLong(4, qty);
            ps.setDouble(5, refPrice);
            ps.setString(6, signalDate.toString());
            ps.setString(7, reasonJson);
            ps.setInt(8, versionId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void markOrderFilled(long id) throws SQLException {
        updateOrderStatus(id, "FILLED", -1);
    }

    public void markOrderCancelled(long id) throws SQLException {
        updateOrderStatus(id, "CANCELLED", -1);
    }

    public void bumpOrderRetry(long id, int retries) throws SQLException {
        updateOrderStatus(id, "PENDING", retries);
    }

    private void updateOrderStatus(long id, String status, int retries) throws SQLException {
        String sql = retries < 0
                ? "UPDATE paper_pending_orders SET status=? WHERE id=?"
                : "UPDATE paper_pending_orders SET status=?, retries=? WHERE id=?";
        try (PreparedStatement ps = qdb.connection().prepareStatement(sql)) {
            ps.setString(1, status);
            if (retries >= 0) {
                ps.setInt(2, retries);
                ps.setLong(3, id);
            } else {
                ps.setLong(2, id);
            }
            ps.executeUpdate();
        }
    }

    public boolean hasPendingOrder(String symbol, String side) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT COUNT(*) FROM paper_pending_orders WHERE symbol=? AND side=? AND status='PENDING'")) {
            ps.setString(1, symbol);
            ps.setString(2, side);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    public void insertTrade(LocalDate signalDate, LocalDate tradeDate, String symbol, String side,
                           double price, long qty, double commission, double stampTax,
                           String reasonJson, int versionId, Double realizedPnl) throws SQLException {
        if (reasonJson == null || reasonJson.isBlank()) {
            throw new IllegalArgumentException("成交必须携带理由：没有理由的交易不允许发生");
        }
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "INSERT INTO paper_trades(signal_date, trade_date, symbol, side, price, qty, commission, stamp_tax, reason_json, version_id, realized_pnl, created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?, datetime('now'))")) {
            ps.setString(1, signalDate.toString());
            ps.setString(2, tradeDate.toString());
            ps.setString(3, symbol);
            ps.setString(4, side);
            ps.setDouble(5, price);
            ps.setLong(6, qty);
            ps.setDouble(7, commission);
            ps.setDouble(8, stampTax);
            ps.setString(9, reasonJson);
            ps.setInt(10, versionId);
            if (realizedPnl == null) ps.setNull(11, Types.REAL);
            else ps.setDouble(11, realizedPnl);
            ps.executeUpdate();
        }
    }

    public List<PaperTrade> loadTradesOn(LocalDate tradeDate) throws SQLException {
        List<PaperTrade> out = new ArrayList<>();
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT id, signal_date, trade_date, symbol, side, price, qty, commission, stamp_tax, reason_json, version_id, realized_pnl " +
                "FROM paper_trades WHERE trade_date=? ORDER BY id")) {
            ps.setString(1, tradeDate.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double pnl = rs.getDouble("realized_pnl");
                    boolean pnlNull = rs.wasNull(); // 必须紧跟 getDouble，否则判的是后读的列
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

    public void saveDailyNav(LocalDate tradeDate, double totalAsset, double cash,
                             double positionValue, double dailyPnl, int versionId) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "INSERT INTO paper_daily_nav(trade_date, total_asset, cash, position_value, daily_pnl, version_id) " +
                "VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(trade_date) DO UPDATE SET total_asset=excluded.total_asset, cash=excluded.cash, " +
                "position_value=excluded.position_value, daily_pnl=excluded.daily_pnl, version_id=excluded.version_id")) {
            ps.setString(1, tradeDate.toString());
            ps.setDouble(2, totalAsset);
            ps.setDouble(3, cash);
            ps.setDouble(4, positionValue);
            ps.setDouble(5, dailyPnl);
            ps.setInt(6, versionId);
            ps.executeUpdate();
        }
    }

    /** 按日期倒序取最近 N 天净值（供连续亏损统计）。 */
    public List<Double> recentDailyPnls(int n) throws SQLException {
        List<Double> out = new ArrayList<>();
        try (Statement st = qdb.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT daily_pnl FROM paper_daily_nav ORDER BY trade_date DESC LIMIT " + n)) {
            while (rs.next()) {
                out.add(rs.getDouble("daily_pnl"));
            }
        }
        return out;
    }

    /** 上一个已记录净值的交易日总资产；无则返回 null（首日用初始资金）。 */
    public Double lastNavBefore(LocalDate date) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT total_asset FROM paper_daily_nav WHERE trade_date < ? ORDER BY trade_date DESC LIMIT 1")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble("total_asset") : null;
            }
        }
    }
}

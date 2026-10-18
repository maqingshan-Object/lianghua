package com.quant.engine;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;

/** 测试辅助：建临时 SQLite 库 + M1 四表 + 合成数据写入。 */
public class TestDb implements AutoCloseable {
    public final Path path;
    public final QuantDb db;
    public final ConfigCenter cc;

    // M1 四表 DDL（与 collector/db.py 一致）
    private static final String M1_SCHEMA = """
            CREATE TABLE stocks (
                symbol TEXT PRIMARY KEY, name TEXT, market TEXT, industry TEXT,
                list_date TEXT, is_st INTEGER DEFAULT 0, suspended INTEGER DEFAULT 0,
                updated_at TEXT);
            CREATE TABLE daily_bars (
                symbol TEXT, trade_date TEXT, open REAL, high REAL, low REAL, close REAL,
                volume REAL, amount REAL, pct_chg REAL, turnover REAL,
                pe_ttm REAL, pb_mrq REAL, suspended INTEGER DEFAULT 0,
                PRIMARY KEY (symbol, trade_date));
            CREATE TABLE fundamentals (
                symbol TEXT, report_date TEXT, announce_date TEXT, roe REAL,
                revenue_yoy REAL, netprofit_yoy REAL, debt_ratio REAL, ocf REAL,
                updated_at TEXT, PRIMARY KEY (symbol, report_date));
            CREATE TABLE collect_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, task TEXT, provider TEXT,
                started_at TEXT, finished_at TEXT, status TEXT,
                symbols_total INTEGER DEFAULT 0, symbols_ok INTEGER DEFAULT 0,
                symbols_fail INTEGER DEFAULT 0, message TEXT);
            """;

    public TestDb() throws Exception {
        this.path = Files.createTempFile("quant-test-", ".db");
        Files.deleteIfExists(path); // 让 sqlite 新建
        this.db = new QuantDb(path.toString());
        // sqlite-jdbc 的 execute() 只执行第一条语句，多表 DDL 必须逐条执行
        try (Statement st = db.connection().createStatement()) {
            for (String ddl : M1_SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
        this.cc = new ConfigCenter(db);
    }

    /** 用默认配置建 v1，effectiveDate 指定（测试用过去日期）。 */
    public void initConfig(LocalDate effectiveDate) throws Exception {
        cc.saveVersion(new java.util.LinkedHashMap<>(StrategyConfig.defaults()), "测试初始化", effectiveDate);
    }

    public void initConfigWith(Map<String, String> changes, LocalDate asOf) throws Exception {
        Map<String, String> m = new java.util.LinkedHashMap<>(StrategyConfig.defaults());
        m.putAll(changes);
        cc.saveVersion(m, "测试", asOf);
    }

    public void insertStock(String symbol, String name, String industry,
                            LocalDate listDate, boolean st, boolean suspended) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO stocks(symbol,name,market,industry,list_date,is_st,suspended,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,datetime('now'))")) {
            ps.setString(1, symbol);
            ps.setString(2, name);
            ps.setString(3, symbol.endsWith(".SH") ? "SH" : "SZ");
            ps.setString(4, industry);
            ps.setString(5, listDate == null ? null : listDate.toString());
            ps.setInt(6, st ? 1 : 0);
            ps.setInt(7, suspended ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /**
     * 生成 n 根单调上涨的日线（收盘 10 + i*step），最近 5 根放量 lastVolMultiple 倍。
     * volume 单位：手；amount = volume*100*close。
     * @param endJumpPct 最后一根额外跳空幅度（小数，如 0.03；用于构造突破信号）
     */
    public void insertRisingBars(String symbol, LocalDate endDate, int n,
                                double step, double baseVol, double lastVolMultiple,
                                double endJumpPct) throws Exception {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,amount,pct_chg,turnover,pe_ttm,pb_mrq,suspended) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < n; i++) {
                LocalDate d = endDate.minusDays(n - 1 - i);
                double close = 10.0 + i * step;
                if (i == n - 1) {
                    close *= (1 + endJumpPct); // 最后一根跳空，构造有效突破
                }
                double vol = (i >= n - 5) ? baseVol * lastVolMultiple : baseVol;
                ps.setString(1, symbol);
                ps.setString(2, d.toString());
                ps.setDouble(3, close * 0.99);
                ps.setDouble(4, close * 1.01);
                ps.setDouble(5, close * 0.98);
                ps.setDouble(6, close);
                ps.setDouble(7, vol);
                ps.setDouble(8, vol * 100 * close);
                ps.setNull(9, java.sql.Types.REAL);
                ps.setNull(10, java.sql.Types.REAL);
                ps.setNull(11, java.sql.Types.REAL);
                ps.setNull(12, java.sql.Types.REAL);
                ps.setInt(13, 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void insertRisingBars(String symbol, LocalDate endDate, int n,
                                double step, double baseVol, double lastVolMultiple) throws Exception {
        insertRisingBars(symbol, endDate, n, step, baseVol, lastVolMultiple, 0.0);
    }

    public void insertFundamental(String symbol, LocalDate reportDate, LocalDate announceDate,
                                 Double roe, Double revenueYoy, Double netprofitYoy,
                                 Double debtRatio, Double ocf) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO fundamentals(symbol,report_date,announce_date,roe,revenue_yoy,netprofit_yoy,debt_ratio,ocf,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?,datetime('now'))")) {
            ps.setString(1, symbol);
            ps.setString(2, reportDate.toString());
            ps.setString(3, announceDate == null ? null : announceDate.toString());
            setDoubleOrNull(ps, 4, roe);
            setDoubleOrNull(ps, 5, revenueYoy);
            setDoubleOrNull(ps, 6, netprofitYoy);
            setDoubleOrNull(ps, 7, debtRatio);
            setDoubleOrNull(ps, 8, ocf);
            ps.executeUpdate();
        }
    }

    private static void setDoubleOrNull(PreparedStatement ps, int idx, Double v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.REAL);
        else ps.setDouble(idx, v);
    }

    @Override
    public void close() throws Exception {
        db.close();
        Files.deleteIfExists(path);
    }
}

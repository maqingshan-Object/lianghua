package com.quant.backtest;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M4 测试辅助：临时库 + M1 四表 + 合成数据。
 * 所有数据均为程序生成的合成数据，仅用于验证回放/指标/报告逻辑。
 */
public class BacktestTestDb implements AutoCloseable {
    public final Path path;
    public final QuantDb db;
    public final ConfigCenter cc;

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

    public BacktestTestDb() throws Exception {
        this.path = Files.createTempFile("quant-backtest-test-", ".db");
        Files.deleteIfExists(path);
        this.db = new QuantDb(path.toString());
        try (Statement st = db.connection().createStatement()) {
            for (String ddl : M1_SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.execute(ddl);
                }
            }
        }
        this.cc = new ConfigCenter(db);
    }

    public void initConfigWith(Map<String, String> changes, LocalDate effectiveDate) throws Exception {
        Map<String, String> m = new LinkedHashMap<>(StrategyConfig.defaults());
        m.putAll(changes);
        cc.saveVersion(m, "测试", effectiveDate);
    }

    public void insertStock(String symbol, String name, String industry,
                            LocalDate listDate, boolean st) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO stocks(symbol,name,market,industry,list_date,is_st,suspended,updated_at) " +
                "VALUES(?,?,?,?,?,?,0,datetime('now'))")) {
            ps.setString(1, symbol);
            ps.setString(2, name);
            ps.setString(3, symbol.endsWith(".SH") ? "SH" : "SZ");
            ps.setString(4, industry);
            ps.setString(5, listDate == null ? null : listDate.toString());
            ps.setInt(6, st ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void insertBars(String symbol, LocalDate endDate, double[][] ohlc, double[] volumes) throws Exception {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,amount,pct_chg,turnover,pe_ttm,pb_mrq,suspended) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < ohlc.length; i++) {
                LocalDate d = endDate.minusDays(ohlc.length - 1 - i);
                double[] r = ohlc[i];
                double vol = volumes == null ? 100000 : volumes[i];
                ps.setString(1, symbol);
                ps.setString(2, d.toString());
                ps.setDouble(3, r[0]);
                ps.setDouble(4, r[1]);
                ps.setDouble(5, r[2]);
                ps.setDouble(6, r[3]);
                ps.setDouble(7, vol);
                ps.setDouble(8, vol * 100 * r[3]);
                if (Double.isNaN(r[4])) ps.setNull(9, Types.REAL);
                else ps.setDouble(9, r[4]);
                ps.setNull(10, Types.REAL);
                ps.setNull(11, Types.REAL);
                ps.setNull(12, Types.REAL);
                ps.setInt(13, 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void insertBars(String symbol, LocalDate endDate, double[][] ohlc) throws Exception {
        insertBars(symbol, endDate, ohlc, null);
    }

    /** n 根单调上涨日线（收盘 10 + i*step），最近 5 根放量 lastVolMultiple 倍。 */
    public void insertRisingBars(String symbol, LocalDate endDate, int n, double step,
                                 double baseVol, double lastVolMultiple) throws Exception {
        double[][] rows = new double[n][5];
        double[] vols = new double[n];
        for (int i = 0; i < n; i++) {
            double close = 10.0 + i * step;
            rows[i][0] = close * 0.99;
            rows[i][1] = close * 1.01;
            rows[i][2] = close * 0.98;
            rows[i][3] = close;
            rows[i][4] = Double.NaN;
            vols[i] = (i >= n - 5) ? baseVol * lastVolMultiple : baseVol;
        }
        insertBars(symbol, endDate, rows, vols);
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
        if (v == null) ps.setNull(idx, Types.REAL);
        else ps.setDouble(idx, v);
    }

    /** 仅关闭连接，不删文件（调用方需要复制库文件时用）。 */
    public void closeConnection() throws Exception {
        db.close();
    }

    @Override
    public void close() throws Exception {
        db.close();
        Files.deleteIfExists(path);
    }
}

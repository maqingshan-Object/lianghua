package com.quant.engine.config;

import com.quant.engine.data.QuantDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置中心：strategy_config（当前生效值）+ strategy_config_version（版本历史）。
 *
 * 约定：
 * - 每个版本快照恒为全量，可独立还原；回滚 = 基于目标版本快照生成新版本（审计链不断）。
 * - 新版本默认下一自然日生效（"下一交易日"的近似，因无交易日历；README 有说明）；
 *   初始 v1 在建库时生成、今日即生效。
 * - 某日选股使用"effective_date <= 该日"的最大版本号，保证版本与选股记录可关联、可复现。
 */
public class ConfigCenter {
    private static final Logger log = LoggerFactory.getLogger(ConfigCenter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QuantDb db;

    public ConfigCenter(QuantDb db) {
        this.db = db;
    }

    /** 建库后首次调用：无任何版本时生成 v1（全默认配置，今日即生效）。 */
    public void ensureDefaults() throws SQLException {
        if (maxVersionNo() == 0) {
            saveVersion(Map.of(), "初始化默认配置", LocalDate.now());
            log.info("生成初始配置版本 v1（今日生效）");
        }
    }

    public Map<String, String> loadAll() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM strategy_config")) {
            while (rs.next()) {
                m.put(rs.getString("key"), rs.getString("value"));
            }
        }
        return m;
    }

    public StrategyConfig currentConfig() throws SQLException {
        return new StrategyConfig(loadAll());
    }

    private void upsert(String key, String value, String now) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO strategy_config(key, value, updated_at) VALUES(?,?,?) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    /**
     * 保存一个新版本：changes 为本次改动的 key->value（增量），其余 key 沿用当前有效值。
     * 快照恒为全量（默认 + 当前表值 + 本次改动），保证任何版本都能独立还原。
     * 返回新版本号。
     */
    public int saveVersion(Map<String, String> changes, String changeNote, LocalDate effectiveDate)
            throws SQLException {
        // 校验：未知 key 拒绝写入，防止拼写错误污染配置
        for (String k : changes.keySet()) {
            if (!StrategyConfig.defaults().containsKey(k)) {
                throw new IllegalArgumentException("未知配置项: " + k);
            }
        }
        Map<String, String> before = fullCurrentConfig();
        Map<String, String> merged = new LinkedHashMap<>(before);
        merged.putAll(changes);
        String diff = new StrategyConfig(before).diff(new StrategyConfig(merged));
        String note = (changeNote == null ? "" : changeNote + "\n") + diff;

        String now = LocalDateTime.now().format(TS);
        for (Map.Entry<String, String> e : merged.entrySet()) {
            upsert(e.getKey(), e.getValue(), now);
        }
        int next = maxVersionNo() + 1;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO strategy_config_version(version_no, created_at, effective_date, changes, snapshot) " +
                "VALUES(?,?,?,?,?)")) {
            ps.setInt(1, next);
            ps.setString(2, now);
            ps.setString(3, effectiveDate.toString());
            ps.setString(4, note);
            ps.setString(5, StrategyConfig.toJson(merged));
            ps.executeUpdate();
        }
        log.info("保存配置版本 v{}，{} 生效", next, effectiveDate);
        return next;
    }

    /** 默认下一自然日生效（"下一交易日"的近似，无交易日历，见 README）。 */
    public int saveVersion(Map<String, String> changes, String changeNote) throws SQLException {
        return saveVersion(changes, changeNote, LocalDate.now().plusDays(1));
    }

    public int saveVersion(String changeNote, LocalDate effectiveDate) throws SQLException {
        return saveVersion(Map.of(), changeNote, effectiveDate);
    }

    /** 当前全量有效配置 = 默认值 + 表中覆盖值（防代码新增 key 导致旧数据缺项）。 */
    private Map<String, String> fullCurrentConfig() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>(StrategyConfig.defaults());
        m.putAll(loadAll());
        return m;
    }

    /** 回滚到历史版本：以目标版本快照为内容生成新版本。返回新版本号。 */
    public int rollbackTo(int targetVersionNo, LocalDate effectiveDate) throws SQLException {
        Map<String, String> snapshot = loadVersionSnapshot(targetVersionNo);
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("版本不存在: v" + targetVersionNo);
        }
        return saveVersion(snapshot, "回滚到 v" + targetVersionNo, effectiveDate);
    }

    /** 某日应使用的版本号（effective_date <= 该日的最大版本）。 */
    public int versionForDate(LocalDate date) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT MAX(version_no) FROM strategy_config_version WHERE effective_date <= ?")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 某日应使用的配置快照（快照覆盖默认值，防代码新增 key 导致旧版本缺项）。 */
    public StrategyConfig configForDate(LocalDate date) throws SQLException {
        int v = versionForDate(date);
        if (v == 0) {
            throw new IllegalStateException("没有任何生效的配置版本，请先 ensureDefaults()");
        }
        Map<String, String> m = new LinkedHashMap<>(StrategyConfig.defaults());
        m.putAll(loadVersionSnapshot(v));
        return new StrategyConfig(m);
    }

    public int versionIdForDate(LocalDate date) throws SQLException {
        int v = versionForDate(date);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT id FROM strategy_config_version WHERE version_no = ?")) {
            ps.setInt(1, v);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                throw new IllegalStateException("版本行缺失: v" + v);
            }
        }
    }

    public Map<String, String> loadVersionSnapshot(int versionNo) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT snapshot FROM strategy_config_version WHERE version_no = ?")) {
            ps.setInt(1, versionNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                return StrategyConfig.fromJson(rs.getString("snapshot"));
            }
        }
    }

    public int maxVersionNo() throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version_no),0) FROM strategy_config_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public record VersionInfo(int versionNo, String createdAt, String effectiveDate, String changes) {}

    public List<VersionInfo> listVersions() throws SQLException {
        List<VersionInfo> out = new ArrayList<>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version_no, created_at, effective_date, changes " +
                     "FROM strategy_config_version ORDER BY version_no")) {
            while (rs.next()) {
                out.add(new VersionInfo(rs.getInt("version_no"), rs.getString("created_at"),
                        rs.getString("effective_date"), rs.getString("changes")));
            }
        }
        return out;
    }
}

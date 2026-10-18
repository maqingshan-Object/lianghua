package com.quant.api.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;

/**
 * 候选股查询：读取最近一次选股运行（select_runs / select_candidates，只读）。
 * histWinRate = 该标的在模拟交易中已平仓交易的胜率（无记录则为 null，如实返回）。
 */
@Service
public class CandidateService extends BaseService {

    public CandidateService(ApiProperties props) {
        super(props);
    }

    public List<Dtos.CandidateDto> candidates(LocalDate date) throws SQLException {
        return withDb(db -> {
            long runId;
            LocalDate runDate;
            int versionNo;
            String sql = date == null
                    ? "SELECT id, run_date, version_id FROM select_runs ORDER BY run_date DESC, id DESC LIMIT 1"
                    : "SELECT id, run_date, version_id FROM select_runs WHERE run_date <= ? ORDER BY run_date DESC, id DESC LIMIT 1";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                if (date != null) {
                    ps.setString(1, date.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return List.of();
                    }
                    runId = rs.getLong(1);
                    runDate = LocalDate.parse(rs.getString(2));
                    versionNo = versionNoOf(db, rs.getInt(3));
                }
            }

            List<Dtos.CandidateDto> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT symbol, name, industry, final_score, ai_score, engine_score, " +
                    "       factor_json, reason, buy_low, buy_high, stop_loss, target " +
                    "FROM select_candidates WHERE run_id = ? ORDER BY rank_no")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String symbol = rs.getString(1);
                        double ai = rs.getDouble(5);
                        Double aiScore = rs.wasNull() ? null : round2(ai);
                        out.add(new Dtos.CandidateDto(
                                symbol,
                                rs.getString(2),
                                rs.getString(3),
                                round2(rs.getDouble(4)),
                                aiScore,
                                round2(rs.getDouble(6)),
                                parseFactorJson(rs.getString(7)),
                                rs.getString(8),
                                round2(rs.getDouble(9)),
                                round2(rs.getDouble(10)),
                                round2(rs.getDouble(11)),
                                round2(rs.getDouble(12)),
                                histWinRate(db, symbol),
                                runDate,
                                versionNo));
                    }
                }
            }
            return out;
        });
    }

    /** 该标的模拟交易已平仓胜率；无已平仓记录返回 null。 */
    private Double histWinRate(QuantDb db, String symbol) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END), COUNT(*) " +
                "FROM paper_trades WHERE symbol = ? AND side = 'SELL' AND realized_pnl IS NOT NULL")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long wins = rs.getLong(1);
                    long total = rs.getLong(2);
                    if (total == 0) {
                        return null;
                    }
                    return round2(wins * 100.0 / total);
                }
                return null;
            }
        }
    }

    private int versionNoOf(QuantDb db, int versionId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT version_no FROM strategy_config_version WHERE id = ?")) {
            ps.setInt(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Map<String, Double> parseFactorJson(String json) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return m;
        }
        // factor_json 由 StrategyConfig.toJson(Map<String,String>) 生成：{"k":"v",...}
        try {
            Map<String, String> raw = StrategyConfig.fromJson(json);
            raw.forEach((k, v) -> {
                try {
                    m.put(k, round2(Double.parseDouble(v)));
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return m;
    }
}

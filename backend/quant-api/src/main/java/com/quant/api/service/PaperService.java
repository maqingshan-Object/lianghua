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
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.QuantDb;
import com.quant.paper.model.PaperAccount;
import com.quant.paper.model.PaperPosition;
import com.quant.paper.model.PaperTrade;

/**
 * 模拟账户查询：总览 / 持仓 / 交易记录（全部只读 paper_* 表）。
 * 持仓止损/目标位按持仓成本 × 当前配置重算（与 M3 口径一致）。
 */
@Service
public class PaperService extends BaseService {

    public PaperService(ApiProperties props) {
        super(props);
    }

    public Dtos.PaperSummary summary() throws SQLException {
        return withDb(db -> {
            PaperAccount acct = loadAccount(db);
            if (acct == null) {
                return new Dtos.PaperSummary(false, 0, 0, 0, 0, null, null, List.of());
            }
            ConfigCenter cc = EngineWiring.configCenter(db);
            double initialCapital = initialCapital(cc);

            List<Dtos.NavPoint> curve = new ArrayList<>();
            Double todayPnl = null;
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT trade_date, total_asset, daily_pnl FROM paper_daily_nav " +
                         "ORDER BY trade_date DESC LIMIT 90")) {
                List<Dtos.NavPoint> rev = new ArrayList<>();
                boolean first = true;
                while (rs.next()) {
                    rev.add(new Dtos.NavPoint(LocalDate.parse(rs.getString(1)), round2(rs.getDouble(2))));
                    if (first) {
                        double p = rs.getDouble(3);
                        todayPnl = rs.wasNull() ? null : round2(p);
                        first = false;
                    }
                }
                for (int i = rev.size() - 1; i >= 0; i--) {
                    curve.add(rev.get(i));
                }
            }
            double marketValue = round2(acct.totalAsset() - acct.cash());
            return new Dtos.PaperSummary(true,
                    round2(acct.totalAsset()), round2(acct.cash()), marketValue,
                    round2(acct.totalAsset() - initialCapital), todayPnl,
                    acct.updatedDate(), curve);
        });
    }

    public List<Dtos.PositionDto> positions() throws SQLException {
        return withDb(db -> {
            List<PaperPosition> list = loadPositions(db);
            if (list.isEmpty()) {
                return List.of();
            }
            ConfigCenter cc = EngineWiring.configCenter(db);
            StrategyConfig cfg;
            try {
                cfg = cc.currentConfig();
            } catch (Exception e) {
                cfg = new StrategyConfig(StrategyConfig.defaults());
            }
            double stopPct = cfg.getDouble(StrategyConfig.TRADE_STOP_LOSS_PCT);
            double takePct = cfg.getDouble(StrategyConfig.TRADE_TAKE_PROFIT_PCT);

            List<Dtos.PositionDto> out = new ArrayList<>();
            for (PaperPosition p : list) {
                Double price = latestClose(db, p.symbol());
                double cost = p.costPrice();
                Double pnl = null, pnlPct = null;
                Double stop = round2(cost * (1 - stopPct / 100));
                Double target = round2(cost * (1 + takePct / 100));
                Double distStop = null, distTarget = null;
                String alert = null;
                if (price != null) {
                    pnl = round2((price - cost) * p.qty());
                    pnlPct = round2((price - cost) / cost * 100);
                    distStop = round2((price - stop) / price * 100);
                    distTarget = round2((target - price) / price * 100);
                    if (price <= stop) {
                        alert = "STOP";
                    } else if (price >= target) {
                        alert = "TARGET";
                    } else if (distStop != null && distStop < 3) {
                        alert = "NEAR_STOP";
                    }
                }
                out.add(new Dtos.PositionDto(p.symbol(), p.name(), p.qty(),
                        round2(cost), price == null ? null : round2(price),
                        pnl, pnlPct, stop, target, distStop, distTarget, alert,
                        p.buyDate(), p.buyReason(), p.versionId()));
            }
            return out;
        });
    }

    public List<Dtos.TradeDto> trades(int limit) throws SQLException {
        int lim = Math.min(Math.max(limit, 1), 1000);
        return withDb(db -> {
            List<Dtos.TradeDto> out = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT signal_date, trade_date, symbol, side, price, qty, " +
                    "       commission, stamp_tax, reason_json, version_id, realized_pnl " +
                    "FROM paper_trades ORDER BY trade_date DESC, id DESC LIMIT ?")) {
                ps.setInt(1, lim);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double fee = round2(rs.getDouble(7) + rs.getDouble(8));
                        double rp = rs.getDouble(11);
                        boolean rpNull = rs.wasNull();
                        out.add(new Dtos.TradeDto(
                                LocalDate.parse(rs.getString(1)),
                                LocalDate.parse(rs.getString(2)),
                                rs.getString(3), rs.getString(4),
                                round2(rs.getDouble(5)), rs.getLong(6), fee,
                                rs.getString(9), rs.getInt(10),
                                rpNull ? null : round2(rp)));
                    }
                }
            }
            return out;
        });
    }

    // ---------- 内部 ----------

    private PaperAccount loadAccount(QuantDb db) throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT cash, total_asset, updated_date, version_id FROM paper_account WHERE id = 1")) {
            if (!rs.next()) {
                return null;
            }
            String d = rs.getString(3);
            return new PaperAccount(rs.getDouble(1), rs.getDouble(2),
                    d == null ? null : LocalDate.parse(d), rs.getInt(4));
        } catch (SQLException e) {
            // 表不存在（M3 未运行过）→ 视为未初始化
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return null;
            }
            throw e;
        }
    }

    private List<PaperPosition> loadPositions(QuantDb db) throws SQLException {
        List<PaperPosition> out = new ArrayList<>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT symbol, name, qty, cost_price, buy_date, buy_reason, version_id " +
                     "FROM paper_positions ORDER BY symbol")) {
            while (rs.next()) {
                out.add(new PaperPosition(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getDouble(4), LocalDate.parse(rs.getString(5)),
                        rs.getString(6), rs.getInt(7)));
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("no such table")) {
                return out;
            }
            throw e;
        }
        return out;
    }

    private Double latestClose(QuantDb db, String symbol) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT close FROM daily_bars WHERE symbol = ? ORDER BY trade_date DESC LIMIT 1")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
                return null;
            }
        }
    }

    private double initialCapital(ConfigCenter cc) {
        try {
            return cc.currentConfig().getDouble(StrategyConfig.PAPER_INITIAL_CAPITAL);
        } catch (Exception e) {
            return 100000;
        }
    }
}

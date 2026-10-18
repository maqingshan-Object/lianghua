package com.quant.paper.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.paper.data.PaperDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * 独立风控模块：一票否决，只管"能不能开新仓"，不管策略逻辑。
 * - 单日亏损超阈值 → 当天停开新仓（卖出不受影响，止损永远放行）
 * - 连续 N 日亏损 → 暂停新开仓并告警（卖出不受影响）
 * 所有拦截都写日志。
 */
public class RiskManager {
    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    public record RiskDecision(boolean haltNewBuys, String reason) {
        public static RiskDecision pass() {
            return new RiskDecision(false, "正常");
        }
    }

    private final PaperDb paperDb;

    public RiskManager(PaperDb paperDb) {
        this.paperDb = paperDb;
    }

    /**
     * @param nav          当日收盘总资产（挂单结算后、选股前）
     * @param prevNav      上一净值日总资产（首日为 null，用初始资金）
     * @param initialCapital 初始资金（首日比较基准）
     */
    public RiskDecision evaluate(double nav, Double prevNav, double initialCapital,
                                 StrategyConfig cfg) throws SQLException {
        double base = prevNav != null ? prevNav : initialCapital;
        double dailyLossPct = base > 0 ? (nav - base) / base * 100.0 : 0;
        double haltPct = cfg.getDouble(StrategyConfig.PAPER_DAILY_LOSS_HALT_PCT);

        if (dailyLossPct <= -haltPct) {
            String reason = String.format("单日亏损 %.2f%% 超过熔断线 %.2f%%，当天停开新仓",
                    dailyLossPct, haltPct);
            log.warn("[风控拦截] {}", reason);
            return new RiskDecision(true, reason);
        }

        int haltDays = cfg.getInt(StrategyConfig.PAPER_CONSECUTIVE_LOSS_HALT_DAYS);
        List<Double> recent = paperDb.recentDailyPnls(haltDays);
        if (recent.size() >= haltDays && recent.stream().allMatch(p -> p < 0)) {
            String reason = String.format("连续 %d 个交易日亏损，暂停新开仓并告警", haltDays);
            log.warn("[风控拦截] {}", reason);
            return new RiskDecision(true, reason);
        }

        return RiskDecision.pass();
    }
}

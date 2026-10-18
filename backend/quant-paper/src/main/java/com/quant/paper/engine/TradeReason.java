package com.quant.paper.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.scoring.Candidate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易理由构建器：每笔挂单/成交必须携带结构化理由 JSON。
 * 没有理由的交易不允许发生（PaperDb 在入库时强制校验）。
 */
public final class TradeReason {
    private TradeReason() {}

    public static final String BUY_CANDIDATE = "BUY_CANDIDATE";
    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String TAKE_PROFIT = "TAKE_PROFIT";
    public static final String SIGNAL_GONE = "SIGNAL_GONE";

    /** 买入理由：候选股信息快照。 */
    public static String buyCandidate(Candidate c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("signal_type", BUY_CANDIDATE);
        m.put("final_score", fmt(c.finalScore()));
        m.put("ai_score", c.aiScore() == null ? "-" : fmt(c.aiScore()));
        m.put("engine_score", fmt(c.engineScore()));
        m.put("buy_low", fmt(c.buyLow()));
        m.put("buy_high", fmt(c.buyHigh()));
        m.put("stop_loss", fmt(c.stopLoss()));
        m.put("target", fmt(c.target()));
        m.put("detail", c.reason());
        return StrategyConfig.toJson(m);
    }

    /** 卖出理由：触发条件快照。 */
    public static String sell(String signalType, String symbol, double costPrice,
                              double closePrice, double stopLoss, double target, String trigger) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("signal_type", signalType);
        m.put("cost_price", fmt(costPrice));
        m.put("close_price", fmt(closePrice));
        m.put("stop_loss", fmt(stopLoss));
        m.put("target", fmt(target));
        m.put("trigger", trigger);
        return StrategyConfig.toJson(m);
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}

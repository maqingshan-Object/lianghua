package com.quant.engine.select;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;

import java.util.List;

/** 候选股的买入区间/止损/目标位计算（参考值，非投资建议）。 */
public class CandidateBuilder {

    public record Levels(double buyLow, double buyHigh, double stopLoss, double target) {}

    public static Levels build(List<Bar> bars, StrategyConfig cfg) {
        Bar last = bars.get(bars.size() - 1);
        double close = last.close();
        // 买入区间：近 5 日最低价 ~ 收盘价
        double low5 = Double.MAX_VALUE;
        for (int i = Math.max(0, bars.size() - 5); i < bars.size(); i++) {
            low5 = Math.min(low5, bars.get(i).low());
        }
        double stopPct = cfg.getDouble(StrategyConfig.TRADE_STOP_LOSS_PCT) / 100.0;
        double profitPct = cfg.getDouble(StrategyConfig.TRADE_TAKE_PROFIT_PCT) / 100.0;
        return new Levels(
                round2(low5),
                round2(close),
                round2(close * (1 - stopPct)),
                round2(close * (1 + profitPct)));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

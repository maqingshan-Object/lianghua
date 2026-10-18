package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;

import java.util.List;

/** 趋势突破：收盘价创 N 日新高（超过前 N 日最高价）。突破幅度越大分数越高。 */
public class BreakoutStrategy implements SignalStrategy {
    @Override public String id() { return "breakout"; }
    @Override public String name() { return "趋势突破"; }

    @Override
    public Signal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        int n = cfg.getInt(StrategyConfig.STRAT_BREAKOUT_DAYS);
        if (bars.size() < n + 1) {
            return Signal.none(id(), "数据不足 " + (n + 1) + " 根");
        }
        Bar last = bars.get(bars.size() - 1);
        double prevHigh = Indicators.highestHighExcludingLast(bars, n);
        if (last.close() <= prevHigh) {
            return Signal.none(id(), "未突破前 " + n + " 日高点");
        }
        double pct = (last.close() - prevHigh) / prevHigh * 100;
        double score = Math.min(100, 50 + pct * 10);
        return new Signal(id(), score,
                String.format("收盘 %.2f 突破前 %d 日高点 %.2f（+%.2f%%）", last.close(), n, prevHigh, pct));
    }
}

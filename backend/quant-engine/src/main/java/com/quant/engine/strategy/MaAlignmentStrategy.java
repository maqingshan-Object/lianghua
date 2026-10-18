package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;

import java.util.List;

/** 均线多头排列：MA5 > MA10 > MA20 > MA60。排列越"开"（间距越大）分数越高。 */
public class MaAlignmentStrategy implements SignalStrategy {
    @Override public String id() { return "ma_alignment"; }
    @Override public String name() { return "均线多头排列"; }

    @Override
    public Signal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        if (bars.size() < 60) {
            return Signal.none(id(), "数据不足 60 根");
        }
        int last = bars.size() - 1;
        double ma5 = Indicators.sma(bars, 5)[last];
        double ma10 = Indicators.sma(bars, 10)[last];
        double ma20 = Indicators.sma(bars, 20)[last];
        double ma60 = Indicators.sma(bars, 60)[last];
        if (!(ma5 > ma10 && ma10 > ma20 && ma20 > ma60)) {
            return Signal.none(id(), "非多头排列");
        }
        // 间距得分：每档间距相对 ma60 的百分比，总和映射到 0-100
        double spread = (ma5 - ma10) / ma60 + (ma10 - ma20) / ma60 + (ma20 - ma60) / ma60;
        double score = Math.min(100, 40 + spread * 1000);
        return new Signal(id(), score,
                String.format("MA5(%.2f)>MA10(%.2f)>MA20(%.2f)>MA60(%.2f)，多头排列", ma5, ma10, ma20, ma60));
    }
}

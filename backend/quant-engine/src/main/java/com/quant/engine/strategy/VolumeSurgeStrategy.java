package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;

import java.util.List;

/** 放量：近 5 日均量 >= ratio × 近 20 日均量（默认 2 倍）。倍数越高分数越高。 */
public class VolumeSurgeStrategy implements SignalStrategy {
    @Override public String id() { return "volume_surge"; }
    @Override public String name() { return "放量"; }

    @Override
    public Signal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        if (bars.size() < 20) {
            return Signal.none(id(), "数据不足 20 根");
        }
        double ratio = cfg.getDouble(StrategyConfig.STRAT_VOLUME_RATIO);
        double shortVol = Indicators.meanVolume(bars, 5);
        double longVol = Indicators.meanVolume(bars, 20);
        if (longVol <= 0) {
            return Signal.none(id(), "成交量数据异常");
        }
        double multiple = shortVol / longVol;
        if (multiple < ratio) {
            return Signal.none(id(), String.format("量比 %.2f 未达 %.1f 倍", multiple, ratio));
        }
        double score = Math.min(100, 50 + (multiple - ratio) * 25);
        return new Signal(id(), score,
                String.format("近 5 日均量为近 20 日均量的 %.2f 倍（阈值 %.1f）", multiple, ratio));
    }
}

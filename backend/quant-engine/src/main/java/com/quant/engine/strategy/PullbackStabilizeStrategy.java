package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;

import java.util.List;

/**
 * 回调均线企稳：价格回踩到 MA20/MA60 附近（最低价距均线 ±3% 内）且近 3 根不再创新低、
 * 收盘站上均线。均线需要 60 根数据。
 */
public class PullbackStabilizeStrategy implements SignalStrategy {
    @Override public String id() { return "pullback"; }
    @Override public String name() { return "回调企稳"; }

    @Override
    public Signal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        if (bars.size() < 60) {
            return Signal.none(id(), "数据不足 60 根");
        }
        int last = bars.size() - 1;
        double ma20 = Indicators.sma(bars, 20)[last];
        double ma60 = Indicators.sma(bars, 60)[last];
        Bar cur = bars.get(last);

        // 回踩哪根均线：取最近被触及的一根（low 进入均线 ±3%）
        Double touchedMa = null;
        String maName = "";
        for (double[] maRef : new double[][]{{ma20}, {ma60}}) {
            double ma = maRef[0];
            if (Math.abs(cur.low() - ma) / ma <= 0.03) {
                touchedMa = ma;
                maName = (ma == ma20) ? "20 日线" : "60 日线";
                break;
            }
        }
        if (touchedMa == null) {
            return Signal.none(id(), "未回踩均线");
        }
        // 企稳：近 3 根最低价不再创新低，且收盘站上被回踩的均线
        for (int i = last - 2; i < last; i++) {
            if (i < 0) continue;
            if (bars.get(i + 1).low() < bars.get(i).low()) {
                return Signal.none(id(), "仍在创新低，未企稳");
            }
        }
        if (cur.close() < touchedMa) {
            return Signal.none(id(), "收盘未站上" + maName);
        }
        return new Signal(id(), 75.0,
                String.format("回踩%s（%.2f）企稳，收盘 %.2f 站上均线", maName, touchedMa, cur.close()));
    }
}

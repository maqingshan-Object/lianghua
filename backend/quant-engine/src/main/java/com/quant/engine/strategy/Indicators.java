package com.quant.engine.strategy;

import com.quant.engine.model.Bar;

import java.util.List;

/** 极简技术指标工具（前复权价直接计算）。 */
public final class Indicators {
    private Indicators() {}

    /** 简单移动平均（收盘价），返回与 bars 等长数组，不足窗口处为 NaN。 */
    public static double[] sma(List<Bar> bars, int n) {
        double[] out = new double[bars.size()];
        double sum = 0;
        for (int i = 0; i < bars.size(); i++) {
            sum += bars.get(i).close();
            if (i >= n) {
                sum -= bars.get(i - n).close();
            }
            out[i] = (i >= n - 1) ? sum / n : Double.NaN;
        }
        return out;
    }

    /** 最近 n 根（含当前）的最高价。 */
    public static double highestHigh(List<Bar> bars, int n) {
        double h = Double.NEGATIVE_INFINITY;
        int from = Math.max(0, bars.size() - n);
        for (int i = from; i < bars.size(); i++) {
            h = Math.max(h, bars.get(i).high());
        }
        return h;
    }

    /** 最近 n 根（不含当前）的最高价，用于"突破前高"判断。 */
    public static double highestHighExcludingLast(List<Bar> bars, int n) {
        if (bars.size() < 2) return Double.NEGATIVE_INFINITY;
        double h = Double.NEGATIVE_INFINITY;
        int from = Math.max(0, bars.size() - 1 - n);
        for (int i = from; i < bars.size() - 1; i++) {
            h = Math.max(h, bars.get(i).high());
        }
        return h;
    }

    /** 最近 n 根成交量均值（不足 n 根则按实际根数）。 */
    public static double meanVolume(List<Bar> bars, int n) {
        int from = Math.max(0, bars.size() - n);
        double sum = 0;
        int cnt = 0;
        for (int i = from; i < bars.size(); i++) {
            sum += bars.get(i).volume();
            cnt++;
        }
        return cnt == 0 ? 0 : sum / cnt;
    }
}

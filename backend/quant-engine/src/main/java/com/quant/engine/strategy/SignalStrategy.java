package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;

import java.util.List;

/**
 * 选股策略统一接口（可插拔）。
 * 输入：标的代码 + 历史 Bar 窗口（时间升序，最后一根为 asOf 当日）+ PIT 基本面快照 + 配置。
 * 输出：0-100 分 + 理由文本。策略内部不允许访问未来数据、不接触资金持仓。
 */
public interface SignalStrategy {
    /** 策略唯一 ID，与配置项 strategy.<id>.enabled / strategy.<id>.weight 对应。 */
    String id();

    /** 策略中文名（用于理由展示）。 */
    String name();

    Signal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg);
}

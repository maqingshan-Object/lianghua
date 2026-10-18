package com.quant.api.service;

import java.sql.SQLException;

import com.quant.api.config.ApiProperties;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.DeepSeekAISignalStrategy;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import com.quant.paper.data.PaperDb;
import com.quant.paper.engine.PaperTrader;
import com.quant.engine.select.SelectorEngine;

/** 引擎装配（与各模块 CLI 的装配方式保持一致）。 */
final class EngineWiring {

    private EngineWiring() {}

    static MarketDataService mds(QuantDb db) {
        return new MarketDataService(db);
    }

    static ConfigCenter configCenter(QuantDb db) {
        return new ConfigCenter(db);
    }

    /** 确保至少有一个生效的配置版本（全新库时生成 v1，幂等）。 */
    static ConfigCenter ensuredConfigCenter(QuantDb db) throws SQLException {
        ConfigCenter cc = new ConfigCenter(db);
        cc.ensureDefaults();
        return cc;
    }

    /**
     * AI 通道装配：aiMock=true → Mock（联调）；
     * aiMock=false → 真实 DeepSeek（Key 从环境变量 DEEPSEEK_API_KEY 读取，
     * 无 Key / 超时 / 报错时由 SelectorEngine 自动降级为 ENGINE_ONLY）。
     */
    private static AISignalStrategy aiStrategy(boolean aiMock) {
        return aiMock ? new MockAISignalStrategy() : new DeepSeekAISignalStrategy();
    }

    static SelectorEngine selector(QuantDb db, boolean aiMock) throws SQLException {
        MarketDataService mds = mds(db);
        ConfigCenter cc = ensuredConfigCenter(db);
        AISignalStrategy ai = aiStrategy(aiMock);
        return new SelectorEngine(mds, cc, new StrategyRegistry(), ai);
    }

    static PaperTrader paperTrader(QuantDb db, boolean aiMock) throws SQLException {
        PaperDb paperDb = new PaperDb(db);
        ConfigCenter cc = configCenter(db); // 注意：不在此处 ensureDefaults（沿用 M3 约定，runDay 内按运行日生成）
        MarketDataService mds = mds(db);
        AISignalStrategy ai = aiStrategy(aiMock);
        SelectorEngine selector = new SelectorEngine(mds, cc, new StrategyRegistry(), ai);
        return new PaperTrader(db, paperDb, cc, mds, selector);
    }
}

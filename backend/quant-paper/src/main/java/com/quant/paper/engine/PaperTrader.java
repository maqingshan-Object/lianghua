package com.quant.paper.engine;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.model.Bar;
import com.quant.engine.model.Stock;
import com.quant.engine.scoring.Candidate;
import com.quant.engine.select.SelectorEngine;
import com.quant.paper.data.PaperDb;
import com.quant.paper.model.PaperAccount;
import com.quant.paper.model.PaperPosition;
import com.quant.paper.model.PaperTrade;
import com.quant.paper.model.PendingOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模拟交易编排（纯模拟，无实盘能力）：
 *
 * 每日 runDay(D) 流程（D 为收盘后）：
 *  1. 结算挂单：signal_date < D 的 PENDING 单，按 D 开盘价成交
 *     （涨停买不到/跌停卖不出/停牌 → 重试，超 N 天作废）
 *  2. 按 D 收盘价估值，记净值曲线
 *  3. 风控检查（熔断只停新开仓，卖出永远放行）
 *  4. 跑 M2 选股 select(D)
 *  5. 卖出：止损/止盈/信号消失（未入选当日候选清单）→ 生成 PENDING 卖单
 *  6. 买入：候选按分排序，预算/仓位上限控制 → 生成 PENDING 买单
 *
 * T+1：买入成交日当天不可卖（buyDate < 信号日才允许生成卖单）。
 */
public class PaperTrader {
    private static final Logger log = LoggerFactory.getLogger(PaperTrader.class);

    public record DaySummary(
            LocalDate date,
            boolean skipped,
            double nav,
            double cash,
            double positionValue,
            double dailyPnl,
            double totalReturnPct,
            int filledBuys,
            int filledSells,
            int newBuyOrders,
            int newSellOrders,
            String riskStatus,
            int versionNo) {}

    private final QuantDb qdb;
    private final PaperDb paperDb;
    private final ConfigCenter configCenter;
    private final MarketDataService mds;
    private final SelectorEngine selector;
    private final Matcher matcher = new Matcher();
    private final RiskManager riskManager;
    private final Map<String, Boolean> stCache = new HashMap<>();

    public PaperTrader(QuantDb qdb, PaperDb paperDb, ConfigCenter configCenter,
                       MarketDataService mds, SelectorEngine selector) {
        this.qdb = qdb;
        this.paperDb = paperDb;
        this.configCenter = configCenter;
        this.mds = mds;
        this.selector = selector;
        this.riskManager = new RiskManager(paperDb);
    }

    public DaySummary runDay(LocalDate date) throws SQLException {
        ensureConfig(date);
        int versionId = configCenter.versionIdForDate(date);
        int versionNo = configCenter.versionForDate(date);
        StrategyConfig cfg = configCenter.configForDate(date);
        double initialCapital = cfg.getDouble(StrategyConfig.PAPER_INITIAL_CAPITAL);
        paperDb.ensureAccount(initialCapital, versionId);

        if (!isTradingDay(date)) {
            log.info("{} 非交易日（全市场无行情），跳过", date);
            return new DaySummary(date, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, "跳过", versionNo);
        }

        // 1. 结算挂单（T+1 开盘成交）
        int[] filled = settlePendingOrders(date, cfg, versionId);

        // 2. 估值 + 记净值
        List<PaperPosition> positions = paperDb.loadPositions();
        PaperAccount acct = paperDb.loadAccount();
        double positionValue = 0;
        for (PaperPosition p : positions) {
            Bar b = barOnOrBefore(date, p.symbol());
            double px = (b == null) ? p.costPrice() : b.close();
            positionValue += px * p.qty();
        }
        positionValue = Matcher.round2(positionValue);
        double nav = Matcher.round2(acct.cash() + positionValue);
        Double prevNav = paperDb.lastNavBefore(date);
        double dailyPnl = Matcher.round2(nav - (prevNav != null ? prevNav : initialCapital));
        paperDb.saveDailyNav(date, nav, acct.cash(), positionValue, dailyPnl, versionId);
        paperDb.saveAccount(acct.cash(), nav, date, versionId);

        // 3. 风控
        RiskManager.RiskDecision risk = riskManager.evaluate(nav, prevNav, initialCapital, cfg);

        // 4. 选股
        SelectorEngine.SelectOutcome outcome = selector.select(date);
        selector.persistRun(date, outcome, qdb, cfg);
        Set<String> candidateSymbols = outcome.candidates().stream()
                .map(Candidate::symbol).collect(Collectors.toSet());

        // 5. 卖出（止损 > 止盈 > 信号消失；T+1；熔断不停卖出）
        int newSellOrders = buildSellOrders(date, positions, candidateSymbols, cfg, versionId);

        // 6. 买入（熔断时停开新仓）
        int newBuyOrders = 0;
        if (!risk.haltNewBuys()) {
            newBuyOrders = buildBuyOrders(date, outcome, positions, nav, positionValue, cfg, versionId);
        } else {
            log.warn("[风控] {} 停开新仓：{}", date, risk.reason());
        }

        double totalReturnPct = Matcher.round2((nav - initialCapital) / initialCapital * 100.0);
        log.info("模拟交易日 {} 完成：净值 {}（今日 {:+.2f}，累计 {:+.2f}%），成交 {} 买 / {} 卖，新增挂单 {} 买 / {} 卖，风控：{}",
                date, nav, dailyPnl, totalReturnPct, filled[0], filled[1], newBuyOrders, newSellOrders, risk.reason());
        return new DaySummary(date, false, nav, acct.cash(), positionValue, dailyPnl,
                totalReturnPct, filled[0], filled[1], newBuyOrders, newSellOrders,
                risk.reason(), versionNo);
    }

    // ---------------- 挂单结算 ----------------

    /** 返回 [买入成交数, 卖出成交数]。 */
    private int[] settlePendingOrders(LocalDate date, StrategyConfig cfg, int versionId) throws SQLException {
        int buyFilled = 0, sellFilled = 0;
        int maxRetries = cfg.getInt(StrategyConfig.PAPER_ORDER_RETRY_DAYS);
        for (PendingOrder o : paperDb.loadPendingOrders()) {
            if (!o.signalDate().isBefore(date)) {
                continue; // 当日信号的单，T+1 才结算
            }
            Bar bar = barOn(date, o.symbol());
            boolean isSt = isSt(o.symbol());
            Matcher.FillResult fr;
            if (bar == null) {
                fr = Matcher.FillResult.fail("当日无行情（停牌），重试");
            } else if (o.isBuy()) {
                fr = matcher.tryFillBuy(bar, isSt, o.qty(), cfg);
            } else {
                fr = matcher.tryFillSell(bar, isSt, o.qty(), cfg);
            }
            if (fr.filled()) {
                applyFill(o, fr, date, cfg, versionId);
                paperDb.markOrderFilled(o.id());
                if (o.isBuy()) buyFilled++; else sellFilled++;
            } else if (o.retries() + 1 > maxRetries) {
                paperDb.markOrderCancelled(o.id());
                log.warn("[挂单作废] {} {} {}股：{}（已重试 {} 天）",
                        o.side(), o.symbol(), o.qty(), fr.failReason(), maxRetries);
            } else {
                paperDb.bumpOrderRetry(o.id(), o.retries() + 1);
                log.info("[挂单重试] {} {} {}股：{}（第 {} 次）",
                        o.side(), o.symbol(), o.qty(), fr.failReason(), o.retries() + 1);
            }
        }
        return new int[]{buyFilled, sellFilled};
    }

    private void applyFill(PendingOrder o, Matcher.FillResult fr, LocalDate date,
                           StrategyConfig cfg, int versionId) throws SQLException {
        PaperAccount acct = paperDb.loadAccount();
        if (o.isBuy()) {
            double cost = Matcher.round2(fr.price() * o.qty() + fr.commission());
            if (acct.cash() < cost) {
                // 下单时已按预算计算，滑点导致略超时保守作废（不透支）
                paperDb.markOrderCancelled(o.id());
                log.warn("[成交作废] 买入 {} {}股 现金不足（需 {}，余 {}）", o.symbol(), o.qty(), cost, acct.cash());
                return;
            }
            paperDb.saveAccount(Matcher.round2(acct.cash() - cost), acct.totalAsset(), date, versionId);
            paperDb.upsertPosition(new PaperPosition(o.symbol(), o.name(), o.qty(),
                    fr.price(), date, o.reasonJson(), versionId));
            paperDb.insertTrade(o.signalDate(), date, o.symbol(), "BUY", fr.price(), o.qty(),
                    fr.commission(), 0, o.reasonJson(), versionId, null);
            log.info("[成交] 买入 {} {}股 @{}（信号日 {}）", o.symbol(), o.qty(), fr.price(), o.signalDate());
        } else {
            PaperPosition pos = findPosition(o.symbol());
            if (pos == null) {
                paperDb.markOrderCancelled(o.id());
                log.warn("[成交作废] 卖出 {} 无持仓，挂单作废", o.symbol());
                return;
            }
            double proceeds = Matcher.round2(fr.price() * o.qty() - fr.commission() - fr.stampTax());
            double pnl = Matcher.round2(proceeds - pos.costPrice() * o.qty());
            paperDb.saveAccount(Matcher.round2(acct.cash() + proceeds), acct.totalAsset(), date, versionId);
            paperDb.deletePosition(o.symbol());
            paperDb.insertTrade(o.signalDate(), date, o.symbol(), "SELL", fr.price(), o.qty(),
                    fr.commission(), fr.stampTax(), o.reasonJson(), versionId, pnl);
            log.info("[成交] 卖出 {} {}股 @{} 实现盈亏 {:+.2f}（信号日 {}）",
                    o.symbol(), o.qty(), fr.price(), pnl, o.signalDate());
        }
    }

    private PaperPosition findPosition(String symbol) throws SQLException {
        for (PaperPosition p : paperDb.loadPositions()) {
            if (p.symbol().equals(symbol)) {
                return p;
            }
        }
        return null;
    }

    // ---------------- 卖出 ----------------

    private int buildSellOrders(LocalDate date, List<PaperPosition> positions,
                                Set<String> candidateSymbols, StrategyConfig cfg,
                                int versionId) throws SQLException {
        int n = 0;
        double stopPct = cfg.getDouble(StrategyConfig.TRADE_STOP_LOSS_PCT);
        double takePct = cfg.getDouble(StrategyConfig.TRADE_TAKE_PROFIT_PCT);
        for (PaperPosition pos : positions) {
            if (!pos.buyDate().isBefore(date)) {
                continue; // T+1：当日买入当日不可卖
            }
            if (paperDb.hasPendingOrder(pos.symbol(), "SELL")) {
                continue;
            }
            Bar bar = barOn(date, pos.symbol());
            if (bar == null) {
                continue;
            }
            // 止损/目标按持仓成本与当前配置重算（配置可能已变更，以当前为准）
            double stop = Matcher.round2(pos.costPrice() * (1 - stopPct / 100.0));
            double target = Matcher.round2(pos.costPrice() * (1 + takePct / 100.0));
            String type = null, trigger = null;
            if (bar.close() <= stop) {
                type = TradeReason.STOP_LOSS;
                trigger = String.format("收盘 %.2f <= 止损位 %.2f", bar.close(), stop);
            } else if (bar.close() >= target) {
                type = TradeReason.TAKE_PROFIT;
                trigger = String.format("收盘 %.2f >= 目标位 %.2f", bar.close(), target);
            } else if (!candidateSymbols.contains(pos.symbol())) {
                type = TradeReason.SIGNAL_GONE;
                trigger = "当日未入选候选清单（跌破阈值或跌出 TopN）";
            }
            if (type != null) {
                String reason = TradeReason.sell(type, pos.symbol(), pos.costPrice(),
                        bar.close(), stop, target, trigger);
                paperDb.insertPendingOrder(pos.symbol(), pos.name(), "SELL", pos.qty(),
                        bar.close(), date, reason, versionId);
                log.info("[卖出信号] {} {}股：{}", pos.symbol(), pos.qty(), trigger);
                n++;
            }
        }
        return n;
    }

    // ---------------- 买入 ----------------

    private int buildBuyOrders(LocalDate date, SelectorEngine.SelectOutcome outcome,
                               List<PaperPosition> positions, double nav, double positionValue,
                               StrategyConfig cfg, int versionId) throws SQLException {
        int n = 0;
        int maxPositions = cfg.getInt(StrategyConfig.TRADE_MAX_POSITIONS);
        double maxPosPct = cfg.getDouble(StrategyConfig.PAPER_MAX_POSITION_PCT);
        double maxTotalPct = cfg.getDouble(StrategyConfig.PAPER_MAX_TOTAL_POSITION_PCT);

        Set<String> held = positions.stream().map(PaperPosition::symbol).collect(Collectors.toSet());
        Set<String> pendingBuy = new HashSet<>();
        for (PendingOrder o : paperDb.loadPendingOrders()) {
            if (o.isBuy()) {
                pendingBuy.add(o.symbol());
            }
        }
        double reservedValue = positionValue;

        for (Candidate c : outcome.candidates()) {
            if (positions.size() + n >= maxPositions) {
                break; // 候选已按分排序，满仓后直接停
            }
            if (held.contains(c.symbol()) || pendingBuy.contains(c.symbol())) {
                continue;
            }
            Bar bar = barOn(date, c.symbol());
            if (bar == null) {
                continue;
            }
            double refPrice = bar.close();
            double budget = nav * maxPosPct / 100.0;
            long qty = (long) (Math.floor(budget / refPrice / 100) * 100); // 整手向下取整
            if (qty < 100) {
                log.info("[买入跳过] {} 资金不足：预算 {} 元，按现价 {} 仅能买 {} 股（不足一手）",
                        c.symbol(), Matcher.round2(budget), refPrice, (long) (budget / refPrice));
                continue;
            }
            if (reservedValue + qty * refPrice > nav * maxTotalPct / 100.0) {
                log.info("[买入跳过] {} 触发总仓位上限 {}%", c.symbol(), maxTotalPct);
                continue;
            }
            paperDb.insertPendingOrder(c.symbol(), c.name(), "BUY", qty, refPrice,
                    date, TradeReason.buyCandidate(c), versionId);
            reservedValue += qty * refPrice;
            log.info("[买入信号] {} {}股 参考价 {}（{}分）", c.symbol(), qty, refPrice, c.finalScore());
            n++;
        }
        return n;
    }

    // ---------------- 行情辅助 ----------------

    /**
     * 全新库时生成 v1（按运行日生效，而非今天——否则跑历史日期时够不着版本）。
     * 已有版本时什么都不做，不干扰"下一自然日生效"语义。
     */
    private void ensureConfig(LocalDate date) throws SQLException {
        if (configCenter.maxVersionNo() == 0) {
            configCenter.saveVersion(java.util.Map.of(), "初始化默认配置", date);
            log.info("生成初始配置版本 v1（{} 生效）", date);
        }
    }

    private boolean isTradingDay(LocalDate date) throws SQLException {
        try (PreparedStatement ps = qdb.connection().prepareStatement(
                "SELECT COUNT(*) FROM daily_bars WHERE trade_date = ?")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** 当日 Bar；无则返回 null（停牌）。 */
    private Bar barOn(LocalDate date, String symbol) throws SQLException {
        for (Bar b : mds.bars(symbol, date, 1)) {
            if (b.tradeDate().equals(date)) {
                return b;
            }
        }
        return null;
    }

    /** 当日或之前最近一根 Bar（估值用）。 */
    private Bar barOnOrBefore(LocalDate date, String symbol) throws SQLException {
        List<Bar> bars = mds.bars(symbol, date, 1);
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    private boolean isSt(String symbol) throws SQLException {
        Boolean cached = stCache.get(symbol);
        if (cached != null) {
            return cached;
        }
        for (Stock s : mds.stocks()) {
            if (s.symbol().equals(symbol)) {
                stCache.put(symbol, s.st());
                return s.st();
            }
        }
        stCache.put(symbol, false);
        return false;
    }
}

package com.quant.paper.engine;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模拟撮合器（A股规则，无实盘能力）：
 * - 信号 T 日收盘产生，T+1 开盘价成交（±滑点）
 * - 涨停买不到 / 跌停卖不出 / 停牌作废
 * - 整手（100 股）由调用方保证；此处只做价格与费用计算
 * - 佣金万2.5（5元保底）+ 印花税卖出千分之一，均可配
 */
public class Matcher {
    private static final Logger log = LoggerFactory.getLogger(Matcher.class);

    /** 涨跌停判断阈值（pct_chg 口径）：主板 10%，ST 5%。用 9.5/4.5 留小数误差余量。 */
    private static final double LIMIT_PCT = 9.5;
    private static final double ST_LIMIT_PCT = 4.5;

    public record FillResult(boolean filled, double price, double commission,
                             double stampTax, String failReason) {
        public static FillResult ok(double price, double commission, double stampTax) {
            return new FillResult(true, price, commission, stampTax, null);
        }

        public static FillResult fail(String reason) {
            return new FillResult(false, 0, 0, 0, reason);
        }
    }

    public static boolean isLimitUp(Bar bar, boolean isSt) {
        if (bar.pctChg() == null) {
            return false;
        }
        return bar.pctChg() >= (isSt ? ST_LIMIT_PCT : LIMIT_PCT);
    }

    public static boolean isLimitDown(Bar bar, boolean isSt) {
        if (bar.pctChg() == null) {
            return false;
        }
        return bar.pctChg() <= -(isSt ? ST_LIMIT_PCT : LIMIT_PCT);
    }

    /** 买入试撮合：返回成交价（含滑点上浮）与佣金。 */
    public FillResult tryFillBuy(Bar bar, boolean isSt, long qty, StrategyConfig cfg) {
        if (bar.suspended()) {
            return FillResult.fail("停牌，买入作废");
        }
        if (isLimitUp(bar, isSt)) {
            return FillResult.fail("涨停买不到");
        }
        double slip = cfg.getDouble(StrategyConfig.PAPER_SLIPPAGE_PCT);
        double price = round2(bar.open() * (1 + slip / 100.0));
        double commission = commission(price * qty, cfg);
        return FillResult.ok(price, commission, 0);
    }

    /** 卖出试撮合：返回成交价（含滑点下浮）、佣金与印花税。 */
    public FillResult tryFillSell(Bar bar, boolean isSt, long qty, StrategyConfig cfg) {
        if (bar.suspended()) {
            return FillResult.fail("停牌，卖出作废");
        }
        if (isLimitDown(bar, isSt)) {
            return FillResult.fail("跌停卖不出");
        }
        double slip = cfg.getDouble(StrategyConfig.PAPER_SLIPPAGE_PCT);
        double price = round2(bar.open() * (1 - slip / 100.0));
        double amount = price * qty;
        double commission = commission(amount, cfg);
        double stampTax = amount * cfg.getDouble(StrategyConfig.PAPER_STAMP_TAX_RATE);
        return FillResult.ok(price, commission, stampTax);
    }

    public double commission(double amount, StrategyConfig cfg) {
        double c = amount * cfg.getDouble(StrategyConfig.PAPER_COMMISSION_RATE);
        return round2(Math.max(c, cfg.getDouble(StrategyConfig.PAPER_COMMISSION_MIN)));
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

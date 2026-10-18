package com.quant.engine.filter;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 第二层·基本面过滤（PIT）：剔除亏损、估值过高、负债率异常、经营现金流为负。
 * <p>
 * PIT 约束：只用 announce_date <= asOf 的财报（由 MarketDataService 保证）。
 * 数据缺失策略：财报缺失 → 通过但标记（次新股常见）；估值数据缺失
 * （AKShare 下 pe_ttm/pb_mrq 为 NULL，见 M1 README 已知坑 #1）→ 跳过估值判断并记日志，
 * 不得因缺数据而误杀。
 */
public class FundamentalFilter implements StockFilter {
    private static final Logger log = LoggerFactory.getLogger(FundamentalFilter.class);

    @Override
    public FilterResult filter(Stock stock, List<Bar> bars, FundamentalSnapshot fund,
                               StrategyConfig cfg, LocalDate asOf, MarketDataService mds) {
        // 1. 盈利要求：用 PIT 快照的 roe / netprofit_yoy 判断
        if (cfg.getBoolean(StrategyConfig.FUND_REQUIRE_PROFIT)) {
            if (fund.isPresent()) {
                Fundamental f = fund.latest().orElseThrow();
                if (isNegative(f.roe()) || isNegative(f.netprofitYoy())) {
                    return FilterResult.reject(
                            String.format("亏损（ROE=%s%%, 净利同比=%s%%）", fmt(f.roe()), fmt(f.netprofitYoy())));
                }
            } else {
                log.debug("{} 无可见财报，跳过盈利判断", stock.symbol());
            }
        }

        // 2. 经营现金流为负
        if (cfg.getBoolean(StrategyConfig.FUND_REQUIRE_OCF_POSITIVE) && fund.isPresent()) {
            Double ocf = fund.latest().orElseThrow().ocf();
            if (ocf != null && ocf < 0) {
                return FilterResult.reject(String.format("经营现金流为负（%.0f 元）", ocf));
            }
        }

        // 3. 资产负债率异常
        if (fund.isPresent()) {
            Double debt = fund.latest().orElseThrow().debtRatio();
            double maxDebt = cfg.getDouble(StrategyConfig.FUND_MAX_DEBT_RATIO);
            if (debt != null && debt > maxDebt) {
                return FilterResult.reject(String.format("资产负债率 %.1f%% 超过上限 %.0f%%", debt, maxDebt));
            }
        }

        // 4. 估值分位（PE/PB 高于行业分位上限则剔除；数据缺失则跳过）
        try {
            FilterResult v = checkValuation(stock, bars, cfg, asOf, mds);
            if (!v.passed()) {
                return v;
            }
        } catch (SQLException e) {
            throw new RuntimeException("估值分位查询失败: " + stock.symbol(), e);
        }

        return FilterResult.pass("通过基本面过滤");
    }

    private FilterResult checkValuation(Stock stock, List<Bar> bars, StrategyConfig cfg,
                                        LocalDate asOf, MarketDataService mds) throws SQLException {
        if (bars.isEmpty()) {
            return FilterResult.pass("无行情，跳过估值");
        }
        Bar last = bars.get(bars.size() - 1);
        String industry = stock.industry();

        if (last.peTtm() != null && industry != null) {
            OptionalDouble pct = mds.industryPePercentile(industry, last.peTtm(), asOf);
            if (pct.isPresent()) {
                double maxPct = cfg.getDouble(StrategyConfig.FUND_MAX_PE_PCT);
                if (pct.getAsDouble() > maxPct) {
                    return FilterResult.reject(String.format(
                            "PE %.1f 处于行业分位 %.0f%%，超过上限 %.0f%%",
                            last.peTtm(), pct.getAsDouble() * 100, maxPct * 100));
                }
            } else {
                log.debug("{} 行业估值数据缺失，跳过 PE 判断", stock.symbol());
            }
        }
        if (last.pbMrq() != null && industry != null) {
            OptionalDouble pct = mds.industryPbPercentile(industry, last.pbMrq(), asOf);
            if (pct.isPresent()) {
                double maxPct = cfg.getDouble(StrategyConfig.FUND_MAX_PB_PCT);
                if (pct.getAsDouble() > maxPct) {
                    return FilterResult.reject(String.format(
                            "PB %.2f 处于行业分位 %.0f%%，超过上限 %.0f%%",
                            last.pbMrq(), pct.getAsDouble() * 100, maxPct * 100));
                }
            } else {
                log.debug("{} 行业估值数据缺失，跳过 PB 判断", stock.symbol());
            }
        }
        return FilterResult.pass("估值通过");
    }

    private static boolean isNegative(Double v) {
        return v != null && v < 0;
    }

    private static String fmt(Double v) {
        return v == null ? "未知" : String.format("%.1f", v);
    }
}

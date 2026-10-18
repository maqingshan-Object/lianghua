package com.quant.api.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** API 响应 DTO（全部为 JSON 序列化友好的 record）。 */
public final class Dtos {

    private Dtos() {}

    // ---------- 市场概况 ----------
    public record IndexInfo(String name, String symbol, Double changePct) {}
    public record HotIndustry(String industry, Double avgChangePct, int count) {}
    public record MarketOverview(
            LocalDate date,
            List<IndexInfo> indices,
            int advance,
            int decline,
            Double totalAmount,
            List<HotIndustry> hotIndustries) {}

    // ---------- 候选股 ----------
    public record CandidateDto(
            String symbol,
            String name,
            String industry,
            double finalScore,
            Double aiScore,
            double engineScore,
            Map<String, Double> factorBreakdown,
            String reason,
            double buyLow,
            double buyHigh,
            double stopLoss,
            double targetPrice,
            Double histWinRate,
            LocalDate runDate,
            int versionNo) {}

    // ---------- 个股详情 ----------
    public record BarDto(LocalDate date, double open, double high, double low, double close, Double volume) {}
    public record MaSeries(List<Double> ma5, List<Double> ma10, List<Double> ma20, List<Double> ma60) {}
    public record SignalEvent(LocalDate date, String strategy) {}
    public record FundamentalDto(
            LocalDate reportDate, LocalDate announceDate,
            Double roe, Double revenueYoy, Double netprofitYoy, Double debtRatio, Double ocf) {}
    public record ScoreBreakdown(
            boolean passedL1, String l1Reason,
            boolean passedL2, String l2Reason,
            double engineScore,
            Double aiScore,
            Map<String, Double> factorScores,
            double finalScore,
            boolean selected,
            String reason,
            double buyLow, double buyHigh, double stopLoss, double targetPrice) {}
    public record StockDetail(
            String symbol, String name, String industry,
            List<BarDto> bars,
            MaSeries ma,
            List<SignalEvent> signals,
            FundamentalDto fundamentals,
            ScoreBreakdown score,
            String conclusion) {}

    // ---------- 模拟交易 ----------
    public record NavPoint(LocalDate date, double totalAsset) {}
    public record PaperSummary(
            boolean initialized,
            double totalAssets,
            double cash,
            double marketValue,
            double totalPnl,
            Double todayPnl,
            LocalDate updatedDate,
            List<NavPoint> navCurve) {}
    public record PositionDto(
            String symbol, String name, long qty,
            double cost, Double price,
            Double pnl, Double pnlPct,
            Double stopLoss, Double targetPrice,
            Double distToStopPct, Double distToTargetPct,
            String alert,
            LocalDate buyDate, String buyReason, int versionId) {}
    public record TradeDto(
            LocalDate signalDate, LocalDate tradeDate,
            String symbol, String side,
            double price, long qty, double fee,
            String reason, int versionId, Double pnl) {}

    // ---------- 回测 ----------
    public record SliceDto(int days, Double totalReturnPct, Double annualizedPct,
                           Double maxDrawdownPct, Double sharpe, Double winRatePct) {}
    public record BacktestMetrics(
            LocalDate from, LocalDate to, int versionNo,
            int tradingDays, int tradeCount,
            Double totalReturnPct, Double annualizedPct, Double maxDrawdownPct,
            Double sharpe, Double winRatePct, Double profitLossRatio, Double avgHoldingDays,
            SliceDto inSample, SliceDto outSample,
            int haltDays, int cancelledOrders,
            List<String> assumptions) {}
    public record VsBenchmark(boolean available, Double benchmarkReturnPct, Double excessReturnPct) {}
    public record BacktestResponse(BacktestMetrics metrics, VsBenchmark vsBenchmark,
                                   String reportUrl, String reportFile) {}

    // ---------- 策略配置 ----------
    public record VersionDto(int versionNo, String createdAt, String effectiveDate, String changes) {}
    public record StrategyConfigDto(
            int versionNo, String effectiveDate,
            Map<String, String> config,
            List<VersionDto> versions) {}
    public record SaveConfigRequest(Map<String, String> changes, String note) {}
    public record RollbackRequest(int versionNo) {}
    public record VersionResult(int versionNo, String effectiveDate) {}

    // ---------- 每日任务 ----------
    public record DailyJobResult(
            String status,
            LocalDate date,
            Map<String, Object> collector,
            Map<String, Object> paper) {}

    // ---------- 通用 ----------
    public record ErrorDto(String error) {}
}

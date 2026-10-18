package com.quant.api.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.quant.api.config.ApiProperties;
import com.quant.api.web.dto.Dtos;
import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.filter.BasicFilter;
import com.quant.engine.filter.FundamentalFilter;
import com.quant.engine.filter.StockFilter;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;
import com.quant.engine.model.Stock;
import com.quant.engine.scoring.FusionMode;
import com.quant.engine.scoring.FusionScorer;
import com.quant.engine.select.CandidateBuilder;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.DeepSeekAISignalStrategy;
import com.quant.engine.strategy.Indicators;
import com.quant.engine.strategy.MockAISignalStrategy;
import com.quant.engine.strategy.SignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;

/**
* 个股详情：K 线 + 均线 + 近 60 日信号事件 + 基本面 + 单股打分拆解 + 规则结论。
* 打分逻辑与 SelectorEngine 的单股分支保持一致（只算一只，不扫全市场）。
*/
@Service
public class StockService extends BaseService {

private static final Logger log = LoggerFactory.getLogger(StockService.class);
private static final int BAR_WINDOW = 120;
private static final int SIGNAL_LOOKBACK = 60;

public StockService(ApiProperties props) {
super(props);
}

public Dtos.StockDetail detail(String symbol, LocalDate date) throws SQLException {
return withDb(db -> {
Stock stock = loadStock(db, symbol);
if (stock == null) {
throw new IllegalArgumentException("标的不存在: " + symbol);
}
LocalDate asOf = date!= null? date: latestBarDate(db, symbol);
if (asOf == null) {
throw new IllegalStateException(symbol + " 暂无行情数据");
}

MarketDataService mds = EngineWiring.mds(db);
ConfigCenter cc = EngineWiring.ensuredConfigCenter(db);
StrategyConfig cfg = cc.configForDate(asOf);
StrategyRegistry registry = new StrategyRegistry();
List<SignalStrategy> enabled = registry.enabled(cfg);

List<Bar> bars = mds.bars(symbol, asOf, BAR_WINDOW);
if (bars.isEmpty()) {
throw new IllegalStateException(symbol + " 在 " + asOf + " 无可用行情");
}

// K 线 + 均线
List<Dtos.BarDto> barDtos = new ArrayList<>();
for (Bar b: bars) {
barDtos.add(new Dtos.BarDto(b.tradeDate(), round2(b.open()), round2(b.high()),
round2(b.low()), round2(b.close()), b.volume()));
}
Dtos.MaSeries ma = new Dtos.MaSeries(
toJsonList(Indicators.sma(bars, 5)),
toJsonList(Indicators.sma(bars, 10)),
toJsonList(Indicators.sma(bars, 20)),
toJsonList(Indicators.sma(bars, 60)));

// 近 60 个交易日的信号事件
List<Dtos.SignalEvent> signals = signalEvents(mds, registry, enabled, cfg, symbol, bars);

// 基本面（PIT：只取当时可见）
FundamentalSnapshot fund = mds.fundamental(symbol, asOf);
Dtos.FundamentalDto fundDto = fund.latest()
.map(this::toFundamentalDto)
.orElse(null);

// 单股打分拆解（镜像 SelectorEngine 单股逻辑）
Dtos.ScoreBreakdown score = analyzeOne(mds, cc, cfg, registry, enabled,
stock, bars, fund, asOf);

String conclusion = buildConclusion(cfg, score);
return new Dtos.StockDetail(stock.symbol(), stock.name(), stock.industry(),
barDtos, ma, signals, fundDto, score, conclusion);
});
}

private List<Dtos.SignalEvent> signalEvents(MarketDataService mds, StrategyRegistry registry,
List<SignalStrategy> enabled, StrategyConfig cfg,
String symbol, List<Bar> bars) {
List<Dtos.SignalEvent> out = new ArrayList<>();
int n = bars.size();
int from = Math.max(0, n - SIGNAL_LOOKBACK);
for (int i = from; i < n; i++) {
LocalDate day = bars.get(i).tradeDate();
List<Bar> window = bars.subList(Math.max(0, i - (BAR_WINDOW - 1)), i + 1);
FundamentalSnapshot fund;
try {
fund = mds.fundamental(symbol, day);
} catch (Exception e) {
log.warn("信号回溯 {} {} 基本面读取失败，跳过", symbol, day, e);
continue;
}
for (SignalStrategy s: enabled) {
Signal sig;
try {
sig = s.score(symbol, window, fund, cfg);
} catch (Exception e) {
continue;
}
if (sig.hasSignal()) {
out.add(new Dtos.SignalEvent(day, s.id()));
}
}
}
return out;
}

private Dtos.ScoreBreakdown analyzeOne(MarketDataService mds, ConfigCenter cc, StrategyConfig cfg,
StrategyRegistry registry, List<SignalStrategy> enabled,
Stock stock, List<Bar> bars, FundamentalSnapshot fund,
LocalDate asOf) throws SQLException {
List<StockFilter> filters = List.of(new BasicFilter(), new FundamentalFilter());
FilterResult r1 = filters.get(0).filter(stock, bars, fund, cfg, asOf, mds);
FilterResult r2 = r1.passed()
? filters.get(1).filter(stock, bars, fund, cfg, asOf, mds)
: FilterResult.reject("未进入基本面过滤");

Map<String, Double> factorScores = new LinkedHashMap<>();
List<String> reasons = new ArrayList<>();
double wSum = 0, sSum = 0;
if (r1.passed() && r2.passed()) {
for (SignalStrategy s: enabled) {
Signal sig;
try {
sig = s.score(stock.symbol(), bars, fund, cfg);
} catch (Exception e) {
sig = Signal.none(s.id(), "策略异常: " + e.getMessage());
}
factorScores.put(s.id(), round2(sig.score()));
if (sig.hasSignal()) {
reasons.add(String.format("%s", s.name(), sig.score(), sig.reason()));
}
double w = registry.weightOf(s, cfg);
wSum += w;
sSum += sig.score() * w;
}
}
double engineScore = wSum > 0? sSum / wSum: 0;

FusionMode mode = FusionMode.parse(cfg.get(StrategyConfig.FUSION_MODE));
Double aiScore = null;
boolean aiAvailable = false;
if (r1.passed() && r2.passed()
&& (mode == FusionMode.AI_ONLY || mode == FusionMode.FUSION)) {
try {
AISignalStrategy.AISignal ai = (props.isAiMock() ? new MockAISignalStrategy() : new DeepSeekAISignalStrategy())
.score(stock.symbol(), bars, fund, cfg);
aiScore = round2(ai.score());
aiAvailable = true;
reasons.add(String.format("%s｜风险：%s", ai.score(), ai.reason(), ai.riskPoints()));
} catch (Exception e) {
log.warn("{} AI 打分失败，降级为纯引擎分", stock.symbol(), e);
mode = FusionMode.ENGINE_ONLY;
}
}

FusionScorer.FusionResult fr = new FusionScorer().fuse(
new FusionScorer.FusionInput(aiScore == null? 0: aiScore, engineScore, aiAvailable), cfg);

CandidateBuilder.Levels lv = CandidateBuilder.build(bars, cfg);
return new Dtos.ScoreBreakdown(
r1.passed(), r1.reason(),
r2.passed(), r2.reason(),
round2(engineScore), aiScore, factorScores,
round2(fr.finalScore()), fr.selected() && r1.passed() && r2.passed(),
String.join("；", reasons),
round2(lv.buyLow()), round2(lv.buyHigh()),
round2(lv.stopLoss()), round2(lv.target()));
}

private String buildConclusion(StrategyConfig cfg, Dtos.ScoreBreakdown s) {
if (!s.passedL1()) {
return "未通过基础过滤（" + s.l1Reason() + "），当前不符合买入条件。";
}
if (!s.passedL2()) {
return "通过基础过滤，但未通过基本面过滤（" + s.l2Reason() + "），当前不符合买入条件。";
}
double threshold = cfg.getDouble(StrategyConfig.FUSION_THRESHOLD);
StringBuilder sb = new StringBuilder();
sb.append(String.format("综合评分 %.1f 分（入选阈值 %.0f），引擎 %.1f 分",
s.finalScore(), threshold, s.engineScore()));
if (s.aiScore()!= null) {
sb.append(String.format("，AI %.1f 分", s.aiScore()));
}
sb.append(s.selected()? "，达到入选标准。": "，未达到入选标准。");
double stopPct = (s.stopLoss() - s.buyHigh()) / s.buyHigh() * 100;
sb.append(String.format("参考买入区间 %.2f~%.2f，止损 %.2f（约 %.1f%%），目标 %.2f。",
s.buyLow(), s.buyHigh(), s.stopLoss(), stopPct, s.targetPrice()));
sb.append("风险点：跌破止损位应离场；基本面以公告数据为准，财报季注意证伪。");
sb.append("以上为规则计算结果，仅供研究，不构成投资建议。");
return sb.toString();
}

private Stock loadStock(QuantDb db, String symbol) throws SQLException {
try (PreparedStatement ps = db.connection().prepareStatement(
"SELECT symbol, name, market, industry, list_date, is_st, suspended FROM stocks WHERE symbol =?")) {
ps.setString(1, symbol);
try (ResultSet rs = ps.executeQuery()) {
if (!rs.next()) {
return null;
}
String ld = rs.getString(5);
return new Stock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
ld == null? null: LocalDate.parse(ld),
rs.getInt(6) == 1, rs.getInt(7) == 1);
}
}
}

private LocalDate latestBarDate(QuantDb db, String symbol) throws SQLException {
try (PreparedStatement ps = db.connection().prepareStatement(
"SELECT MAX(trade_date) FROM daily_bars WHERE symbol =?")) {
ps.setString(1, symbol);
try (ResultSet rs = ps.executeQuery()) {
if (rs.next() && rs.getString(1)!= null) {
return LocalDate.parse(rs.getString(1));
}
return null;
}
}
}

private List<Double> toJsonList(double[] arr) {
List<Double> out = new ArrayList<>(arr.length);
for (double v: arr) {
out.add(Double.isNaN(v)? null: round2(v));
}
return out;
}

private Dtos.FundamentalDto toFundamentalDto(Fundamental f) {
return new Dtos.FundamentalDto(
f.reportDate(), f.announceDate(),
round2OrNull(f.roe()), round2OrNull(f.revenueYoy()),
round2OrNull(f.netprofitYoy()), round2OrNull(f.debtRatio()),
f.ocf());
}
}

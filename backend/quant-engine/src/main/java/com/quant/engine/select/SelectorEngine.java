package com.quant.engine.select;

import com.quant.engine.config.ConfigCenter;
import com.quant.engine.config.StrategyConfig;
import com.quant.engine.data.MarketDataService;
import com.quant.engine.data.QuantDb;
import com.quant.engine.filter.BasicFilter;
import com.quant.engine.filter.FundamentalFilter;
import com.quant.engine.filter.StockFilter;
import com.quant.engine.model.Bar;
import com.quant.engine.model.FilterResult;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.model.Signal;
import com.quant.engine.model.Stock;
import com.quant.engine.scoring.Candidate;
import com.quant.engine.scoring.FusionMode;
import com.quant.engine.scoring.FusionScorer;
import com.quant.engine.strategy.AISignalStrategy;
import com.quant.engine.strategy.SignalStrategy;
import com.quant.engine.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 选股引擎编排：L1 基础过滤 → L2 基本面过滤 → L3 策略打分（引擎通道）
* → AI 通道打分 → 融合 → 取 TopN。
*/
public class SelectorEngine {
private static final Logger log = LoggerFactory.getLogger(SelectorEngine.class);
private static final int BAR_WINDOW = 120;

private final MarketDataService mds;
private final ConfigCenter configCenter;
private final StrategyRegistry registry;
private final AISignalStrategy aiStrategy; // 可为 null（AI 通道关闭时）
private final List<StockFilter> filters = List.of(new BasicFilter(), new FundamentalFilter());
private final FusionScorer fusion = new FusionScorer();

public SelectorEngine(MarketDataService mds, ConfigCenter configCenter,
StrategyRegistry registry, AISignalStrategy aiStrategy) {
this.mds = mds;
this.configCenter = configCenter;
this.registry = registry;
this.aiStrategy = aiStrategy;
}

public record SelectOutcome(List<Candidate> candidates, int universe,
int passedL1, int passedL2, int versionId, int versionNo) {}

public SelectOutcome select(LocalDate asOf) throws SQLException {
int versionId = configCenter.versionIdForDate(asOf);
int versionNo = configCenter.versionForDate(asOf);
StrategyConfig cfg = configCenter.configForDate(asOf);
FusionMode mode = FusionMode.parse(cfg.get(StrategyConfig.FUSION_MODE));
int topN = cfg.getInt(StrategyConfig.SELECT_TOP_N);

List<Stock> universe = mds.stocks();
int passedL1 = 0, passedL2 = 0;
List<Scored> scored = new ArrayList<>();

for (Stock stock: universe) {
List<Bar> bars = mds.bars(stock.symbol(), asOf, BAR_WINDOW);
FundamentalSnapshot fund = mds.fundamental(stock.symbol(), asOf);

FilterResult r1 = filters.get(0).filter(stock, bars, fund, cfg, asOf, mds);
if (!r1.passed()) {
continue;
}
passedL1++;
FilterResult r2 = filters.get(1).filter(stock, bars, fund, cfg, asOf, mds);
if (!r2.passed()) {
continue;
}
passedL2++;

// 引擎通道：启用的策略加权
List<SignalStrategy> enabled = registry.enabled(cfg);
Map<String, Double> factorScores = new LinkedHashMap<>();
List<String> reasons = new ArrayList<>();
double wSum = 0, sSum = 0;
for (SignalStrategy s: enabled) {
Signal sig;
try {
sig = s.score(stock.symbol(), bars, fund, cfg);
} catch (Exception e) {
log.warn("{} 策略 {} 执行异常，按 0 分处理", stock.symbol(), s.id(), e);
sig = Signal.none(s.id(), "策略异常: " + e.getMessage());
}
factorScores.put(s.id(), sig.score());
if (sig.hasSignal()) {
reasons.add(String.format("【%s %.0f分】%s", s.name(), sig.score(), sig.reason()));
}
double w = registry.weightOf(s, cfg);
wSum += w;
sSum += sig.score() * w;
}
double engineScore = wSum > 0? sSum / wSum: 0;

// AI 通道
Double aiScore = null;
boolean aiAvailable = false;
if ((mode == FusionMode.AI_ONLY || mode == FusionMode.FUSION) && aiStrategy!= null) {
try {
AISignalStrategy.AISignal ai = aiStrategy.score(stock.symbol(), bars, fund, cfg);
aiScore = ai.score();
aiAvailable = true;
reasons.add(String.format("【AI %.0f分】%s｜风险：%s", ai.score(), ai.reason(), ai.riskPoints()));
} catch (Exception e) {
// 失败降级：AI 不可用时按 ENGINE_ONLY 继续，不阻塞选股
log.warn("{} AI 打分失败，降级为纯引擎分", stock.symbol(), e);
mode = FusionMode.ENGINE_ONLY;
}
}

FusionScorer.FusionResult fr = fusion.fuse(
new FusionScorer.FusionInput(
aiScore == null? 0: aiScore, engineScore, aiAvailable),
cfg);
if (!fr.selected()) {
continue;
}

CandidateBuilder.Levels lv = CandidateBuilder.build(bars, cfg);
scored.add(new Scored(new Candidate(
stock.symbol(), stock.name(), stock.industry(),
round2(fr.finalScore()), aiScore, round2(engineScore),
factorScores, String.join("；", reasons),
lv.buyLow(), lv.buyHigh(), lv.stopLoss(), lv.target()),
fr.finalScore()));
}

scored.sort(Comparator.comparingDouble(Scored::score).reversed());
List<Candidate> top = scored.stream()
.limit(topN)
.map(Scored::candidate)
.toList();

log.info("选股完成：全市场 {} → L1 {} → L2 {} → 入选 {}（{}，配置 v{}）",
universe.size(), passedL1, passedL2, top.size(), mode, versionNo);
return new SelectOutcome(top, universe.size(), passedL1, passedL2, versionId, versionNo);
}

private record Scored(Candidate candidate, double score) {}

private static double round2(double v) {
return Math.round(v * 100.0) / 100.0;
}

/** 持久化一次选股运行（含 version_id 关联）。返回 run_id。 */
public long persistRun(LocalDate asOf, SelectOutcome outcome, QuantDb db, StrategyConfig cfg)
throws SQLException {
String mode = cfg.get(StrategyConfig.FUSION_MODE);
long runId;
try (var ps = db.connection().prepareStatement(
"INSERT INTO select_runs(run_date, version_id, mode, top_n, universe, passed_l1, passed_l2, candidates, created_at) " +
"VALUES(?,?,?,?,?,?,?,?, datetime('now'))",
java.sql.Statement.RETURN_GENERATED_KEYS)) {
ps.setString(1, asOf.toString());
ps.setInt(2, outcome.versionId());
ps.setString(3, mode);
ps.setInt(4, outcome.candidates().size());
ps.setInt(5, outcome.universe());
ps.setInt(6, outcome.passedL1());
ps.setInt(7, outcome.passedL2());
ps.setInt(8, outcome.candidates().size());
ps.executeUpdate();
try (var rs = ps.getGeneratedKeys()) {
rs.next();
runId = rs.getLong(1);
}
}
try (var ps = db.connection().prepareStatement(
"INSERT INTO select_candidates(run_id, rank_no, symbol, name, industry, final_score, ai_score, engine_score, factor_json, reason, buy_low, buy_high, stop_loss, target) " +
"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
int rank = 1;
for (Candidate c: outcome.candidates()) {
ps.setLong(1, runId);
ps.setInt(2, rank++);
ps.setString(3, c.symbol());
ps.setString(4, c.name());
ps.setString(5, c.industry());
ps.setDouble(6, c.finalScore());
if (c.aiScore() == null) ps.setNull(7, java.sql.Types.REAL);
else ps.setDouble(7, c.aiScore());
ps.setDouble(8, c.engineScore());
ps.setString(9, StrategyConfig.toJson(toStrMap(c.factorScores())));
ps.setString(10, c.reason());
ps.setDouble(11, c.buyLow());
ps.setDouble(12, c.buyHigh());
ps.setDouble(13, c.stopLoss());
ps.setDouble(14, c.target());
ps.addBatch();
}
ps.executeBatch();
}
log.info("选股运行已持久化：run_id={}", runId);
return runId;
}

private static Map<String, String> toStrMap(Map<String, Double> m) {
Map<String, String> out = new LinkedHashMap<>();
m.forEach((k, v) -> out.put(k, String.valueOf(v)));
return out;
}
}

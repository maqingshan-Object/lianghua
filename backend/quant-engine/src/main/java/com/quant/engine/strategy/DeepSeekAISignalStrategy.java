package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.util.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 通道真实实现：DeepSeek API（OpenAI 兼容接口）给股票打分。
 *
 * <p>铁律：
 * <ul>
 *   <li>API Key 只从环境变量 {@value #ENV_API_KEY} 读取，
 *       绝不出现在代码、日志、注释、测试中；</li>
 *   <li>无 Key / 超时 / HTTP 错误 / 返回非 JSON → 抛 {@link AiScoringException}，
 *       由 SelectorEngine 的降级逻辑接住，自动切 ENGINE_ONLY，不阻塞选股；</li>
 *   <li>Prompt 模板版本化（resources/prompt/stock-score-v1.txt），改模板 = 改策略版本；</li>
 *   <li>只做精排：调用方（SelectorEngine）已把候选池限定为 TopN，此处只管单标的打分。</li>
 * </ul>
 */
public class DeepSeekAISignalStrategy implements AISignalStrategy {
    public static final String ID = "ai_deepseek";
    public static final String ENV_API_KEY = "DEEPSEEK_API_KEY";
    public static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    public static final String MODEL = "deepseek-chat";
    public static final String PROMPT_VERSION = "stock-score-v1";

    private static final String PROMPT_RESOURCE = "/prompt/stock-score-v1.txt";
    private static final double TEMPERATURE = 0.1;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String SYSTEM_PROMPT =
            "你是 A 股量化投研助手。根据提供的技术面与基本面数据，给这只股票未来 5-20 个交易日的上涨机会打分。只输出 JSON。";

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAISignalStrategy.class);

    private final String apiKey; // 可为 null/blank → score() 时抛明确异常走降级
    private final String apiUrl;
    private final HttpExchange exchange;
    private final Duration timeout;

    /** 生产构造：Key 从环境变量读取。 */
    public DeepSeekAISignalStrategy() {
        this(System.getenv(ENV_API_KEY), DEFAULT_API_URL, defaultExchange(), DEFAULT_TIMEOUT);
    }

    /** 测试构造：注入 key/地址/HTTP 交换实现/超时，不碰真实网络。 */
    DeepSeekAISignalStrategy(String apiKey, String apiUrl, HttpExchange exchange, Duration timeout) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.exchange = exchange;
        this.timeout = timeout;
    }

    /**
     * HTTP 交换抽象：生产走 {@link HttpClient}，测试注入 fake。
     * （沙箱禁直连 TCP，连 127.0.0.1 的 stub 服务器也不可用，故用函数式注入。）
     */
    @FunctionalInterface
    interface HttpExchange {
        HttpResult post(String url, String authHeader, String body, Duration timeout)
                throws IOException, InterruptedException;
    }

    /** 极简 HTTP 结果：状态码 + 响应体。 */
    record HttpResult(int status, String body) {}

    private static HttpExchange defaultExchange() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return (url, auth, body, timeout) -> {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(resp.statusCode(), resp.body());
        };
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AISignal score(String symbol, List<Bar> bars, FundamentalSnapshot fund, StrategyConfig cfg) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiScoringException("未设置环境变量 " + ENV_API_KEY + "，AI 打分通道不可用");
        }
        if (bars == null || bars.isEmpty()) {
            throw new AiScoringException(symbol + " 无 K 线数据，无法打分");
        }
        String prompt = buildPrompt(symbol, bars, fund);
        String content = callApi(prompt);
        return parseAiSignal(symbol, content);
    }

    // ------------------------------------------------------------------
    // Prompt 构造
    // ------------------------------------------------------------------

    private String buildPrompt(String symbol, List<Bar> bars, FundamentalSnapshot fund) {
        String tpl = loadTemplate();
        return tpl.replace("{{symbol}}", symbol)
                .replace("{{tech_summary}}", techSummary(bars))
                .replace("{{fund_summary}}", fundSummary(fund));
    }

    private String loadTemplate() {
        try (InputStream in = getClass().getResourceAsStream(PROMPT_RESOURCE)) {
            if (in == null) {
                throw new AiScoringException("Prompt 模板缺失：" + PROMPT_RESOURCE + "（打包问题）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AiScoringException("读取 Prompt 模板失败", e);
        }
    }

    /** 技术指标摘要：现价、均线、位置、量比。数据不足处标"—"，不编造。 */
    private String techSummary(List<Bar> bars) {
        Bar last = bars.get(bars.size() - 1);
        double[] ma5 = Indicators.sma(bars, 5);
        double[] ma10 = Indicators.sma(bars, 10);
        double[] ma20 = Indicators.sma(bars, 20);
        double[] ma60 = Indicators.sma(bars, 60);
        int i = bars.size() - 1;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "现价 %.2f（%s）；", last.close(), last.tradeDate()));
        sb.append("MA5 ").append(fmt(ma5[i])).append(" / MA10 ").append(fmt(ma10[i]))
                .append(" / MA20 ").append(fmt(ma20[i])).append(" / MA60 ").append(fmt(ma60[i])).append("；");
        if (!Double.isNaN(ma60[i]) && ma60[i] != 0) {
            sb.append(String.format(Locale.ROOT, "现价相对MA60 %+.1f%%；",
                    (last.close() / ma60[i] - 1) * 100));
        }
        double hh20 = Indicators.highestHigh(bars, 20);
        double ll20 = lowestLow(bars, 20);
        sb.append(String.format(Locale.ROOT, "近20日最高 %.2f（距今 %+.1f%%）/最低 %.2f；",
                hh20, (last.close() / hh20 - 1) * 100, ll20));
        double vr = Indicators.meanVolume(bars, 5);
        double vr20 = Indicators.meanVolume(bars, 20);
        sb.append(vr20 > 0
                ? String.format(Locale.ROOT, "量比（5日均量/20日均量）%.1f；", vr / vr20)
                : "量比 数据不足；");
        sb.append("近5日涨跌幅：");
        for (int k = Math.max(0, bars.size() - 5); k < bars.size(); k++) {
            Double p = bars.get(k).pctChg();
            sb.append(p == null ? "—" : String.format(Locale.ROOT, "%+.1f%%", p)).append(" ");
        }
        return sb.toString().trim();
    }

    private String fundSummary(FundamentalSnapshot fund) {
        if (!fund.isPresent()) {
            return "PIT 快照：选股日当时无已公告财报（次新股常见），按数据缺失处理。";
        }
        Fundamental f = fund.latest().orElseThrow();
        return String.format(Locale.ROOT,
                "ROE %s / 营收同比 %s / 净利润同比 %s / 资产负债率 %s / 经营现金流 %s（报告期 %s，公告日期 %s）",
                pct(f.roe()), pct(f.revenueYoy()), pct(f.netprofitYoy()), pct(f.debtRatio()),
                f.ocf() == null ? "—" : String.format(Locale.ROOT, "%.2f亿元", f.ocf() / 1e8),
                f.reportDate(), f.announceDate());
    }

    private static double lowestLow(List<Bar> bars, int n) {
        double l = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, bars.size() - n); k < bars.size(); k++) {
            l = Math.min(l, bars.get(k).low());
        }
        return l;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%+.1f%%", v);
    }

    // ------------------------------------------------------------------
    // API 调用（含 1 次重试）
    // ------------------------------------------------------------------

    private String callApi(String prompt) {
        String body = buildRequestBody(prompt);
        String auth = "Bearer " + apiKey;
        AiScoringException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResult r = exchange.post(apiUrl, auth, body, timeout);
                int sc = r.status();
                if (sc == 200) {
                    return extractContent(r.body());
                }
                if (sc == 429 || sc >= 500) {
                    last = new AiScoringException("DeepSeek API 返回 HTTP " + sc + "（可重试）");
                    log.warn("DeepSeek API 返回 {}（第{}次），2秒后重试", sc, attempt);
                    sleepQuietly(2000);
                    continue;
                }
                throw new AiScoringException("DeepSeek API 返回 HTTP " + sc + "（不重试）");
            } catch (HttpTimeoutException e) {
                last = new AiScoringException(
                        "DeepSeek API 超时（" + timeout.getSeconds() + "s）", e);
                log.warn("DeepSeek API 超时（第{}次），2秒后重试", attempt);
                sleepQuietly(2000);
            } catch (IOException e) {
                throw new AiScoringException("DeepSeek API 调用失败: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiScoringException("DeepSeek API 调用被中断", e);
            }
        }
        throw last;
    }

    private String buildRequestBody(String prompt) {
        return "{\"model\":" + MiniJson.quote(MODEL)
                + ",\"temperature\":" + TEMPERATURE
                + ",\"response_format\":{\"type\":\"json_object\"}"
                + ",\"messages\":["
                + "{\"role\":\"system\",\"content\":" + MiniJson.quote(SYSTEM_PROMPT) + "},"
                + "{\"role\":\"user\",\"content\":" + MiniJson.quote(prompt) + "}"
                + "]}";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    /** 从 chat/completions 响应中取出 choices[0].message.content。 */
    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) {
        try {
            Map<String, Object> root = MiniJson.parseObject(respBody);
            Object choices = root.get("choices");
            if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
                throw new AiScoringException("DeepSeek 返回无 choices 字段");
            }
            Object first = ((List<?>) choices).get(0);
            if (!(first instanceof Map)) {
                throw new AiScoringException("DeepSeek 返回 choices[0] 非对象");
            }
            Object msg = ((Map<String, Object>) first).get("message");
            if (!(msg instanceof Map)) {
                throw new AiScoringException("DeepSeek 返回无 message 字段");
            }
            Object content = ((Map<String, Object>) msg).get("content");
            if (!(content instanceof String) || ((String) content).isBlank()) {
                throw new AiScoringException("DeepSeek 返回 content 为空");
            }
            return (String) content;
        } catch (IllegalArgumentException e) {
            throw new AiScoringException("DeepSeek 返回非 JSON: " + e.getMessage(), e);
        }
    }

    /** 解析模型输出的 {"score","reason","risks"}，兼容 markdown 代码块包裹。 */
    private AISignal parseAiSignal(String symbol, String content) {
        String json = stripFences(content);
        final Map<String, Object> m;
        try {
            m = MiniJson.parseObject(json);
        } catch (IllegalArgumentException e) {
            throw new AiScoringException(symbol + " AI 返回 JSON 解析失败: " + abbrev(json), e);
        }
        Object s = m.get("score");
        Object r = m.get("reason");
        Object k = m.get("risks");
        if (!(s instanceof Double) || !(r instanceof String) || !(k instanceof String)) {
            throw new AiScoringException(
                    symbol + " AI 返回缺字段（需要 score/reason/risks）: " + abbrev(json));
        }
        // AISignal record 构造器校验 0-100，越界抛 IllegalArgumentException → 上层降级
        return new AISignal((Double) s, (String) r, (String) k);
    }

    /** 去掉可能的代码块包裹；顺手处理前后多余文字（取首个 { 到末个 }）。 */
    private static String stripFences(String content) {
        String t = content.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : "";
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
            t = t.trim();
        }
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            t = t.substring(a, b + 1);
        }
        return t;
    }

    private static String abbrev(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}

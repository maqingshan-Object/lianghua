package com.quant.engine.strategy;

import com.quant.engine.config.StrategyConfig;
import com.quant.engine.model.Bar;
import com.quant.engine.model.Fundamental;
import com.quant.engine.model.FundamentalSnapshot;
import com.quant.engine.strategy.DeepSeekAISignalStrategy.HttpExchange;
import com.quant.engine.strategy.DeepSeekAISignalStrategy.HttpResult;
import com.quant.engine.util.MiniJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeepSeekAISignalStrategy 测试：HTTP 层全部用函数式 fake 注入，
 * 绝不调用真实 DeepSeek API；测试里只用假 Key（"test-key"），真 Key 永不进测试。
 * （沙箱禁直连 TCP，连 127.0.0.1 的 stub 服务器也不可用，故不用 HttpServer。）
 */
class DeepSeekAISignalStrategyTest {

    /** 把模型"返回"的内层 JSON 包进标准 chat/completions 外层结构。 */
    private static String okBody(String innerJson) {
        String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"chatcmpl-x\",\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{}}";
    }

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private DeepSeekAISignalStrategy strat(HttpExchange ex) {
        return new DeepSeekAISignalStrategy("test-key", "https://fake.invalid/v1", ex, Duration.ofSeconds(10));
    }

    private HttpExchange ok(String innerJson) {
        return (url, auth, body, timeout) -> {
            hits.incrementAndGet();
            lastAuth.set(auth);
            lastBody.set(body);
            return new HttpResult(200, okBody(innerJson));
        };
    }

    private List<Bar> bars70() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 2);
        for (int i = 0; i < 70; i++) {
            double c = 10 + i * 0.1;
            bars.add(new Bar("600519.SH", d.plusDays(i),
                    c * 0.99, c * 1.01, c * 0.98, c,
                    100000, 1e8, 1.0, 2.0, null, null, false));
        }
        return bars;
    }

    private FundamentalSnapshot fund() {
        Fundamental f = new Fundamental("600519.SH",
                LocalDate.of(2024, 9, 30), LocalDate.of(2024, 10, 29),
                18.5, 12.3, 9.8, 45.2, 1.2e8);
        return new FundamentalSnapshot("600519.SH", LocalDate.of(2025, 3, 12), Optional.of(f));
    }

    private StrategyConfig cfg() {
        return new StrategyConfig(new LinkedHashMap<>(StrategyConfig.defaults()));
    }

    // ---------------- 成功路径 ----------------

    @Test
    void 标准响应解析成功() {
        var s = strat(ok("{\"score\":78,\"reason\":\"均线多头，量能配合\",\"risks\":\"追高风险\"}"));

        AISignalStrategy.AISignal sig = s.score("600519.SH", bars70(), fund(), cfg());

        assertThat(sig.score()).isEqualTo(78.0);
        assertThat(sig.reason()).isEqualTo("均线多头，量能配合");
        assertThat(sig.riskPoints()).isEqualTo("追高风险");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        assertThat(lastBody.get()).contains("600519.SH");
        assertThat(lastBody.get()).contains("\"temperature\":0.1");
        assertThat(lastBody.get()).contains("deepseek-chat");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 模型返回被markdown代码块包裹也能解析() {
        var s = strat(ok("```json\n{\"score\":61,\"reason\":\"企稳\",\"risks\":\"破位风险\"}\n```"));

        AISignalStrategy.AISignal sig = s.score("600519.SH", bars70(), fund(), cfg());

        assertThat(sig.score()).isEqualTo(61.0);
    }

    @Test
    void 内容前后有多余文字也能提取JSON() {
        var s = strat(ok("好的，这是我的分析：{\"score\":55,\"reason\":\"中性\",\"risks\":\"震荡\"} 以上仅供参考"));

        AISignalStrategy.AISignal sig = s.score("600519.SH", bars70(), fund(), cfg());

        assertThat(sig.score()).isEqualTo(55.0);
    }

    // ---------------- 异常路径 ----------------

    @Test
    void 分数越界抛异常走降级() {
        var s = strat(ok("{\"score\":120,\"reason\":\"x\",\"risks\":\"y\"}"));

        assertThatThrownBy(() -> s.score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 缺字段抛AiScoringException() {
        var s = strat(ok("{\"score\":70,\"reason\":\"只有两个字段\"}"));

        assertThatThrownBy(() -> s.score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining("缺字段");
    }

    @Test
    void choices为空抛AiScoringException() {
        HttpExchange ex = (u, a, b, t) -> new HttpResult(200, "{\"id\":\"x\",\"choices\":[]}");

        assertThatThrownBy(() -> strat(ex).score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class);
    }

    @Test
    void HTTP500重试一次后抛异常() {
        HttpExchange ex = (u, a, b, t) -> {
            hits.incrementAndGet();
            return new HttpResult(500, "{\"error\":\"busy\"}");
        };

        assertThatThrownBy(() -> strat(ex).score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining("500");
        assertThat(hits.get()).isEqualTo(2); // 1 次 + 重试 1 次
    }

    @Test
    void HTTP401不重试直接抛异常() {
        HttpExchange ex = (u, a, b, t) -> {
            hits.incrementAndGet();
            return new HttpResult(401, "{\"error\":\"bad key\"}");
        };

        assertThatThrownBy(() -> strat(ex).score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining("401");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 超时重试后抛AiScoringException() {
        HttpExchange ex = (u, a, b, t) -> {
            hits.incrementAndGet();
            throw new HttpTimeoutException("timed out");
        };

        assertThatThrownBy(() -> strat(ex).score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining("超时");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void 网络IO错误直接抛异常不重试() {
        HttpExchange ex = (u, a, b, t) -> {
            hits.incrementAndGet();
            throw new IOException("connection reset");
        };

        assertThatThrownBy(() -> strat(ex).score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining("IOException");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 未配置Key抛明确异常() {
        var s1 = new DeepSeekAISignalStrategy(null, "https://fake.invalid/", null, Duration.ofSeconds(2));
        assertThatThrownBy(() -> s1.score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class)
                .hasMessageContaining(DeepSeekAISignalStrategy.ENV_API_KEY);

        var s2 = new DeepSeekAISignalStrategy("  ", "https://fake.invalid/", null, Duration.ofSeconds(2));
        assertThatThrownBy(() -> s2.score("600519.SH", bars70(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class);
    }

    @Test
    void 无K线抛AiScoringException() {
        var s = strat(ok("{\"score\":70,\"reason\":\"x\",\"risks\":\"y\"}"));

        assertThatThrownBy(() -> s.score("600519.SH", List.of(), fund(), cfg()))
                .isInstanceOf(AiScoringException.class);
        assertThat(hits.get()).isZero(); // 参数校验在前，不应发请求
    }

    // ---------------- MiniJson ----------------

    @Test
    void MiniJson转义与解析往返() {
        String raw = "a\"b\\c\n中";
        String quoted = MiniJson.quote(raw);
        var m = MiniJson.parseObject("{\"k\":" + quoted + ",\"n\":1.5,\"t\":true,\"z\":null,"
                + "\"arr\":[1,\"x\"],\"obj\":{\"a\":2}}");
        assertThat(m.get("k")).isEqualTo(raw);
        assertThat(m.get("n")).isEqualTo(1.5);
        assertThat(m.get("t")).isEqualTo(Boolean.TRUE);
        assertThat(m.get("z")).isNull();
        assertThat(m.get("arr")).isEqualTo(java.util.List.of(1.0, "x"));
        assertThat(((java.util.Map<?, ?>) m.get("obj")).get("a")).isEqualTo(2.0);
    }

    @Test
    void MiniJson非法输入抛异常() {
        assertThatThrownBy(() -> MiniJson.parseObject("{bad}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiniJson.parseObject("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

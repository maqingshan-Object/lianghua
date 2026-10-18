package com.quant.engine.config;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 策略配置的类型化快照。所有 key 集中在此，缺省值即默认值。
 * 配置变更走 ConfigCenter.saveVersion()，每次生成新版本并留 diff。
 */
public class StrategyConfig {

    // ---- 第一层：基础过滤 ----
    public static final String FILTER_MIN_AMOUNT = "filter.min_amount";           // 日成交额下限（元）
    public static final String FILTER_MIN_LIST_YEARS = "filter.min_list_years";   // 上市年限下限（年）

    // ---- 第二层：基本面过滤 ----
    public static final String FUND_MAX_PE_PCT = "fund.max_pe_industry_pct";       // PE 行业分位上限 0-1
    public static final String FUND_MAX_PB_PCT = "fund.max_pb_industry_pct";       // PB 行业分位上限 0-1
    public static final String FUND_MAX_DEBT_RATIO = "fund.max_debt_ratio";        // 资产负债率上限 %
    public static final String FUND_REQUIRE_PROFIT = "fund.require_profit";        // 要求盈利 true/false
    public static final String FUND_REQUIRE_OCF_POSITIVE = "fund.require_ocf_positive";

    // ---- 第三层：技术面策略开关与权重 ----
    public static final String STRAT_MA_ALIGN_ENABLED = "strategy.ma_alignment.enabled";
    public static final String STRAT_MA_ALIGN_WEIGHT = "strategy.ma_alignment.weight";
    public static final String STRAT_BREAKOUT_ENABLED = "strategy.breakout.enabled";
    public static final String STRAT_BREAKOUT_WEIGHT = "strategy.breakout.weight";
    public static final String STRAT_BREAKOUT_DAYS = "strategy.breakout.days";     // N 日新高
    public static final String STRAT_VOLUME_ENABLED = "strategy.volume_surge.enabled";
    public static final String STRAT_VOLUME_WEIGHT = "strategy.volume_surge.weight";
    public static final String STRAT_VOLUME_RATIO = "strategy.volume_surge.ratio"; // 放量倍数
    public static final String STRAT_PULLBACK_ENABLED = "strategy.pullback.enabled";
    public static final String STRAT_PULLBACK_WEIGHT = "strategy.pullback.weight";

    // ---- 双通道融合 ----
    public static final String FUSION_MODE = "fusion.mode";               // ENGINE_ONLY / AI_ONLY / FUSION
    public static final String FUSION_AI_WEIGHT = "fusion.ai_weight";
    public static final String FUSION_ENGINE_WEIGHT = "fusion.engine_weight";
    public static final String FUSION_THRESHOLD = "fusion.threshold";     // 入选阈值 0-100

    // ---- 选股与交易参数 ----
    public static final String SELECT_TOP_N = "select.top_n";
    public static final String TRADE_STOP_LOSS_PCT = "trade.stop_loss_pct";
    public static final String TRADE_TAKE_PROFIT_PCT = "trade.take_profit_pct";
    public static final String TRADE_MAX_POSITIONS = "trade.max_positions";

    // ---- 模拟交易（M3 paper 模块，全部版本化管理）----
    public static final String PAPER_INITIAL_CAPITAL = "paper.initial_capital";       // 初始虚拟资金（元）
    public static final String PAPER_MAX_POSITION_PCT = "paper.max_position_pct";     // 单只仓位上限 %（相对总资产）
    public static final String PAPER_MAX_TOTAL_POSITION_PCT = "paper.max_total_position_pct"; // 总仓位上限 %
    public static final String PAPER_COMMISSION_RATE = "paper.commission_rate";       // 佣金费率（小数）
    public static final String PAPER_COMMISSION_MIN = "paper.commission_min";         // 佣金单笔保底（元）
    public static final String PAPER_STAMP_TAX_RATE = "paper.stamp_tax_rate";         // 印花税率（卖出，小数）
    public static final String PAPER_SLIPPAGE_PCT = "paper.slippage_pct";             // 单边滑点 %
    public static final String PAPER_DAILY_LOSS_HALT_PCT = "paper.daily_loss_halt_pct"; // 单日亏损熔断 %
    public static final String PAPER_CONSECUTIVE_LOSS_HALT_DAYS = "paper.consecutive_loss_halt_days"; // 连续亏损暂停天数
    public static final String PAPER_ORDER_RETRY_DAYS = "paper.order_retry_days";     // 挂单无法成交最大重试天数

    private final Map<String, String> values;

    public StrategyConfig(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    /** 全部默认值。 */
    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(FILTER_MIN_AMOUNT, "50000000");
        m.put(FILTER_MIN_LIST_YEARS, "1.0");
        m.put(FUND_MAX_PE_PCT, "0.8");
        m.put(FUND_MAX_PB_PCT, "0.8");
        m.put(FUND_MAX_DEBT_RATIO, "70");
        m.put(FUND_REQUIRE_PROFIT, "true");
        m.put(FUND_REQUIRE_OCF_POSITIVE, "true");
        m.put(STRAT_MA_ALIGN_ENABLED, "true");
        m.put(STRAT_MA_ALIGN_WEIGHT, "1.0");
        m.put(STRAT_BREAKOUT_ENABLED, "true");
        m.put(STRAT_BREAKOUT_WEIGHT, "1.0");
        m.put(STRAT_BREAKOUT_DAYS, "60");
        m.put(STRAT_VOLUME_ENABLED, "true");
        m.put(STRAT_VOLUME_WEIGHT, "1.0");
        m.put(STRAT_VOLUME_RATIO, "2.0");
        m.put(STRAT_PULLBACK_ENABLED, "true");
        m.put(STRAT_PULLBACK_WEIGHT, "1.0");
        m.put(FUSION_MODE, "ENGINE_ONLY");
        m.put(FUSION_AI_WEIGHT, "0.5");
        m.put(FUSION_ENGINE_WEIGHT, "0.5");
        m.put(FUSION_THRESHOLD, "60");
        m.put(SELECT_TOP_N, "10");
        m.put(TRADE_STOP_LOSS_PCT, "8");
        m.put(TRADE_TAKE_PROFIT_PCT, "20");
        m.put(TRADE_MAX_POSITIONS, "10");
        m.put(PAPER_INITIAL_CAPITAL, "100000");
        m.put(PAPER_MAX_POSITION_PCT, "20");
        m.put(PAPER_MAX_TOTAL_POSITION_PCT, "80");
        m.put(PAPER_COMMISSION_RATE, "0.00025");
        m.put(PAPER_COMMISSION_MIN, "5");
        m.put(PAPER_STAMP_TAX_RATE, "0.001");
        m.put(PAPER_SLIPPAGE_PCT, "0.1");
        m.put(PAPER_DAILY_LOSS_HALT_PCT, "3");
        m.put(PAPER_CONSECUTIVE_LOSS_HALT_DAYS, "5");
        m.put(PAPER_ORDER_RETRY_DAYS, "3");
        return m;
    }

    public String get(String key) {
        return values.get(key);
    }

    public double getDouble(String key) {
        return Double.parseDouble(values.get(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(values.get(key));
    }

    public int getInt(String key) {
        return Integer.parseInt(values.get(key));
    }

    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    /** 极简 JSON 序列化（value 均为字符串，无需引入 Jackson）。 */
    public static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    /**
     * JSON 反序列化：完整解析扁平 {"k":"v"} 结构（容忍空格、转义），
     * 不依赖第三方库。只支持字符串值。
     */
    public static Map<String, String> fromJson(String json) {
        Map<String, String> m = new LinkedHashMap<>();
        int[] p = {0};
        ws(json, p);
        expect(json, p, '{');
        ws(json, p);
        if (peek(json, p) == '}') {
            p[0]++;
            return m;
        }
        while (true) {
            ws(json, p);
            String key = str(json, p);
            ws(json, p);
            expect(json, p, ':');
            ws(json, p);
            String val = str(json, p);
            m.put(key, val);
            ws(json, p);
            char c = peek(json, p);
            if (c == ',') {
                p[0]++;
            } else if (c == '}') {
                p[0]++;
                break;
            } else {
                throw new IllegalArgumentException("JSON 解析失败，位置 " + p[0] + ": " + json);
            }
        }
        return m;
    }

    private static void ws(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static char peek(String s, int[] p) {
        if (p[0] >= s.length()) {
            throw new IllegalArgumentException("JSON 意外结束: " + s);
        }
        return s.charAt(p[0]);
    }

    private static void expect(String s, int[] p, char want) {
        char c = peek(s, p);
        if (c != want) {
            throw new IllegalArgumentException("JSON 解析失败，位置 " + p[0] + " 期望 '" + want + "' 实际 '" + c + "'");
        }
        p[0]++;
    }

    private static String str(String s, int[] p) {
        expect(s, p, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek(s, p);
            if (c == '"') {
                p[0]++;
                return sb.toString();
            }
            if (c == '\\') {
                p[0]++;
                char e = peek(s, p);
                sb.append(switch (e) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> e;
                });
                p[0]++;
            } else {
                sb.append(c);
                p[0]++;
            }
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 与另一份配置做 diff，返回 "key: old -> new" 多行文本。 */
    public String diff(StrategyConfig other) {
        StringBuilder sb = new StringBuilder();
        for (String k : values.keySet()) {
            String a = values.get(k);
            String b = other.values.get(k);
            if (b == null) {
                sb.append(k).append(": ").append(a).append(" -> (删除)\n");
            } else if (!a.equals(b)) {
                sb.append(k).append(": ").append(a).append(" -> ").append(b).append('\n');
            }
        }
        for (String k : other.values.keySet()) {
            if (!values.containsKey(k)) {
                sb.append(k).append(": (新增) -> ").append(other.values.get(k)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 该配置在 asOf 日期是否已过"上市年限"下限（供过滤器用）。 */
    public static boolean listedLongEnough(LocalDate listDate, LocalDate asOf, double minYears) {
        if (listDate == null) return false;
        return !listDate.plusDays((long) (minYears * 365)).isAfter(asOf);
    }
}

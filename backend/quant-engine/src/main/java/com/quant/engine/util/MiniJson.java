package com.quant.engine.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具（quant-engine 无 Jackson 依赖，手写解析器）。
 * <p>
 * 只够"拼 DeepSeek 请求体 + 解析 API 响应"用，不是通用库：
 * 支持对象、数组、字符串（含转义）、数字、true/false/null。
 */
public final class MiniJson {
    private MiniJson() {
    }

    /** JSON 字符串转义并加引号（用于拼请求体）。 */
    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 解析顶层必须为对象的 JSON，返回 LinkedHashMap（保持字段顺序）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = new Parser(json).parseValue();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("顶层不是 JSON 对象");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> expect("true", Boolean.TRUE);
                case 'f' -> expect("false", Boolean.FALSE);
                case 'n' -> expect("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new IllegalArgumentException("对象 key 必须是字符串，位置 " + pos);
                }
                String k = parseString();
                skipWs();
                if (peek() != ':') {
                    throw new IllegalArgumentException("对象 key 后缺少冒号，位置 " + pos);
                }
                pos++;
                m.put(k, parseValue());
                skipWs();
                char d = peek();
                if (d == ',') {
                    pos++;
                } else if (d == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("对象内非法字符 '" + d + "'，位置 " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                skipWs();
                char d = peek();
                if (d == ',') {
                    pos++;
                } else if (d == ']') {
                    pos++;
                    return l;
                } else {
                    throw new IllegalArgumentException("数组内非法字符 '" + d + "'，位置 " + pos);
                }
            }
        }

        private String parseString() {
            pos++; // opening "
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("字符串转义意外结束");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("\\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("非法值，位置 " + pos);
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字: " + s.substring(start, pos));
            }
        }

        private Object expect(String lit, Object val) {
            if (s.startsWith(lit, pos)) {
                pos += lit.length();
                return val;
            }
            throw new IllegalArgumentException("非法字面量，位置 " + pos);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : 0;
        }
    }
}

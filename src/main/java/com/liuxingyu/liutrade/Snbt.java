package com.liuxingyu.liutrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 SNBT 解析：只处理 {@code custom_data} 常见的复合标签
 * （字符串 / 数字带后缀 b s L f d / 布尔 / 嵌套复合 / 列表）。
 * 无法解析时返回 null，调用方应忽略。
 */
final class Snbt {

    private final String text;
    private int index;

    private Snbt(String text) {
        this.text = text;
    }

    static Map<String, Object> parseCompound(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            Snbt parser = new Snbt(trimmed);
            parser.skipWhitespace();
            Map<String, Object> result = parser.compound();
            parser.skipWhitespace();
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> compound() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = readKey();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            out.put(key, value());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
            } else if (c == '}') {
                index++;
                break;
            } else {
                throw new IllegalStateException();
            }
        }
        return out;
    }

    private Object value() {
        char c = peek();
        if (c == '{') {
            return compound();
        }
        if (c == '[') {
            return list();
        }
        if (c == '"' || c == '\'') {
            return quoted();
        }
        return scalar();
    }

    private List<Object> list() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(value());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                index++;
            } else if (c == ']') {
                index++;
                break;
            } else {
                throw new IllegalStateException();
            }
        }
        return out;
    }

    private String readKey() {
        char c = peek();
        if (c == '"' || c == '\'') {
            return quoted();
        }
        int start = index;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == ':' || ch == ',' || ch == '}' || Character.isWhitespace(ch)) {
                break;
            }
            index++;
        }
        return text.substring(start, index);
    }

    private String quoted() {
        char quote = text.charAt(index++);
        StringBuilder sb = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '\\' && index < text.length()) {
                sb.append(text.charAt(index++));
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        throw new IllegalStateException();
    }

    private Object scalar() {
        int start = index;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == ',' || c == '}' || c == ']') {
                break;
            }
            index++;
        }
        String raw = text.substring(start, index).trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException();
        }
        if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(raw);
        }
        char last = raw.charAt(raw.length() - 1);
        String number = raw;
        try {
            switch (last) {
                case 'b', 'B' -> {
                    return Byte.parseByte(number.substring(0, number.length() - 1));
                }
                case 's', 'S' -> {
                    return Short.parseShort(number.substring(0, number.length() - 1));
                }
                case 'l', 'L' -> {
                    return Long.parseLong(number.substring(0, number.length() - 1));
                }
                case 'f', 'F' -> {
                    return Float.parseFloat(number.substring(0, number.length() - 1));
                }
                case 'd', 'D' -> {
                    return Double.parseDouble(number.substring(0, number.length() - 1));
                }
                default -> {
                }
            }
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalStateException();
        }
        return text.charAt(index);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalStateException();
        }
        index++;
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}

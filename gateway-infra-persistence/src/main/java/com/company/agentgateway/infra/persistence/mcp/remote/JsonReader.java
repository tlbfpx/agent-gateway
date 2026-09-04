package com.company.agentgateway.infra.persistence.mcp.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON parser(R18 #1 P0 自实现;R18+1 swap Jackson)。
 *
 * <p>支持：string/number/boolean/null/object/array;token 流。
 */
class JsonReader {

    private final String s;
    private int pos;

    JsonReader(String s) {
        this.s = s;
        this.pos = 0;
    }

    Object parseValue() {
        skipWs();
        if (pos >= s.length()) return null;
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBool();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { pos++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            if (peek() == ',') { pos++; continue; }
            if (peek() == '}') { pos++; return m; }
            throw new IllegalStateException("expected , or } at " + pos);
        }
    }

    List<Object> parseArray() {
        expect('[');
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') { pos++; return a; }
        while (true) {
            a.add(parseValue());
            skipWs();
            if (peek() == ',') { pos++; continue; }
            if (peek() == ']') { pos++; return a; }
            throw new IllegalStateException("expected , or ] at " + pos);
        }
    }

    String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < s.length()) {
                char n = s.charAt(pos++);
                sb.append(switch (n) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        throw new IllegalStateException("unterminated string");
    }

    Boolean parseBool() {
        if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        throw new IllegalStateException("invalid bool at " + pos);
    }

    Object parseNull() {
        if (s.startsWith("null", pos)) { pos += 4; return null; }
        throw new IllegalStateException("invalid null at " + pos);
    }

    Number parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
        String n = s.substring(start, pos);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.valueOf(n);
        return Long.valueOf(n);
    }

    void expect(char c) {
        skipWs();
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw new IllegalStateException("expected " + c + " at " + pos);
        }
        pos++;
    }

    char peek() {
        skipWs();
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
}

package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny, dependency-free JSON parser sufficient for Mojang's manifests.
 * Supports objects, arrays, strings, numbers, booleans, null.
 * Not meant as a general JSON library.
 */
public final class Json {

    private final String src;
    private final int len;
    private final AtomicInteger pos = new AtomicInteger(0);

    private Json(String src) {
        this.src = src;
        this.len = src.length();
    }

    public static Object parse(String s) {
        return new Json(s).parseValue();
    }

    public static Object parse(Path p) throws IOException {
        String s;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            s = readAll(r);
        }
        return parse(s);
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private Object parseValue() {
        skipWs();
        if (pos.get() >= len) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBool();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { pos.incrementAndGet(); return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            m.put(key, parseValue());
            skipWs();
            char c = next();
            if (c == ',') continue;
            if (c == '}') break;
            throw new IllegalArgumentException("Expected , or } at " + (pos.get()-1));
        }
        return m;
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') { pos.incrementAndGet(); return a; }
        while (true) {
            a.add(parseValue());
            skipWs();
            char c = next();
            if (c == ',') continue;
            if (c == ']') break;
            throw new IllegalArgumentException("Expected , or ] at " + (pos.get()-1));
        }
        return a;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char e = next();
                sb.append(switch (e) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> {
                        String hex = nextN(4);
                        yield (char) Integer.parseInt(hex, 16);
                    }
                    default -> e;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Number parseNumber() {
        int start = pos.get();
        if (peek() == '-') pos.incrementAndGet();
        while (pos.get() < len) {
            char c = peek();
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos.incrementAndGet();
            } else break;
        }
        String num = src.substring(start, pos.get());
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    private Object parseBool() {
        if (src.startsWith("true", pos.get())) {
            pos.addAndGet(4);
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos.get())) {
            pos.addAndGet(5);
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos.get());
    }

    private Object parseNull() {
        if (src.startsWith("null", pos.get())) {
            pos.addAndGet(4);
            return null;
        }
        throw new IllegalArgumentException("Invalid literal at " + pos.get());
    }

    // -- helpers --
    private void skipWs() {
        while (pos.get() < len) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos.incrementAndGet();
            else break;
        }
    }
    private char peek() { return src.charAt(pos.get()); }
    private char next() { return src.charAt(pos.get() < len ? pos.getAndIncrement() : len-1); }
    private String nextN(int n) { int s = pos.get(); pos.addAndGet(n); return src.substring(s, s+n); }
    private void expect(char want) {
        char c = next();
        if (c != want) throw new IllegalArgumentException("Expected '" + want + "' at " + (pos.get()-1) + " but got '" + c + "'");
    }

    // -- convenience accessors --
    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }
    public static Object obj(Map<String, Object> m, String key) {
        return m.get(key);
    }
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? Collections.emptyList() : (List<Object>) v;
    }
    public static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? Collections.emptyMap() : (Map<String, Object>) v;
    }
    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v == null ? def : (Boolean) v;
    }
}

package aurora.launcher;

import java.util.*;

/**
 * Tiny JSON serializer for writing small config/account documents back to disk.
 * Handles strings, numbers, booleans, null, lists and maps (LinkedHashMap order).
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Boolean || o instanceof Number) { sb.append(o); return; }
        if (o instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"', '\\' -> { sb.append('\\'); sb.append(c); }
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : c);
                }
            }
            sb.append('"');
            return;
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('\n').append("  ");
                write(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append(m.isEmpty() ? "{}" : "\n}");
            return;
        }
        if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                sb.append(' ');
                write(sb, e);
            }
            sb.append(first ? "[]" : " ]");
            return;
        }
        sb.append('"').append(o.toString()).append('"');
    }
}

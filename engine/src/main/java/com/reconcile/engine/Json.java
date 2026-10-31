package com.reconcile.engine;

import java.util.Collection;
import java.util.Map;

/** Tiny JSON writer for reports (maps, lists, strings, numbers, booleans). */
public final class Json {
    private Json() {}

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        append(sb, o, 0);
        return sb.append('\n').toString();
    }

    private static void append(StringBuilder sb, Object o, int depth) {
        String pad = "  ".repeat(depth + 1), end = "  ".repeat(depth);
        if (o == null) sb.append("null");
        else if (o instanceof String s) sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
        else if (o instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : String.format("%.4f", d));
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(pad).append('"').append(e.getKey()).append("\": ");
                append(sb, e.getValue(), depth + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append(end).append('}');
        } else if (o instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            int i = 0;
            for (Object x : c) {
                sb.append(pad);
                append(sb, x, depth + 1);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            sb.append(end).append(']');
        } else sb.append('"').append(o).append('"');
    }
}

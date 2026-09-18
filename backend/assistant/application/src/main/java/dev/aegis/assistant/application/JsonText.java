package dev.aegis.assistant.application;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer for tool results and schemas built from plain Java values (maps, collections, strings, numbers,
 * booleans, null). Other objects are written as their string form. Keeps the application free of JSON libraries.
 */
public final class JsonText {
    private JsonText() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Boolean bool -> out.append(bool);
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case java.math.BigDecimal number -> out.append(number.toPlainString());
            case Number number -> appendNumber(out, number);
            case Map<?, ?> map -> appendObject(out, map);
            case Collection<?> items -> appendArray(out, items.iterator());
            case Object[] items -> appendArray(out, java.util.Arrays.asList(items).iterator());
            default -> appendString(out, value.toString());
        }
    }

    private static void appendNumber(StringBuilder out, Number number) {
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null");
        } else {
            out.append(number);
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, String.valueOf(entry.getKey()));
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, Iterator<?> items) {
        out.append('[');
        boolean first = true;
        while (items.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            append(out, items.next());
        }
        out.append(']');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

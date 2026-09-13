package dev.aegis.portability.application;

import java.util.List;

/** Minimal RFC 4180 writer for readable exports. */
final class Csv {
    private Csv() {
    }

    static String render(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        appendRow(out, header);
        for (List<String> row : rows) {
            appendRow(out, row);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(row.get(i)));
        }
        out.append("\r\n");
    }

    static String quote(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r");
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}

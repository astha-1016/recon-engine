package com.reconcile.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small RFC-4180 CSV reader/writer so the engine has no third-party dependencies. */
public final class Csv {
    private Csv() {}

    public static List<Map<String, String>> read(Path path) throws IOException {
        return parseRecords(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static List<Map<String, String>> read(InputStream in) throws IOException {
        return parseRecords(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    public static List<Map<String, String>> parseRecords(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = parse(text);
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> header = new ArrayList<>();
        for (String h : rows.get(0)) header.add(h.trim());
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() == 1 && r.get(0).isEmpty()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) m.put(header.get(c), c < r.size() ? r.get(c) : "");
            out.add(m);
        }
        return out;
    }

    static List<List<String>> parse(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    public static void write(Path path, List<String> header, List<List<String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder(String.join(",", header)).append('\n');
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                String v = r.get(i);
                sb.append(v.contains(",") || v.contains("\"") || v.contains("\n") ? '"' + v.replace("\"", "\"\"") + '"' : v);
            }
            sb.append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}

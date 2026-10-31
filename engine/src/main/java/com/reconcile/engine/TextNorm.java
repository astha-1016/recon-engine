package com.reconcile.engine;

import com.reconcile.engine.Model.PayerKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalisation of names, payer identifiers and invoice references. */
public final class TextNorm {
    private TextNorm() {}

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("trd", "traders"), Map.entry("trdrs", "traders"), Map.entry("engg", "engineering"),
            Map.entry("eng", "engineering"), Map.entry("txtl", "textiles"), Map.entry("txtls", "textiles"),
            Map.entry("supp", "supplies"), Map.entry("ent", "enterprises"), Map.entry("mfg", "manufacturing"));
    private static final Set<String> LEGAL_SUFFIXES = Set.of("pvt", "ltd", "limited", "private", "p", "llp", "inc", "llc", "co", "corp");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)([6-9]\\d{9})(?!\\d)");
    private static final Pattern MASK = Pattern.compile("(?i)^\\s*[x*#]{2,}[\\s-]*(\\d{3,4})\\s*$");
    private static final Pattern INVOICE_REF = Pattern.compile("(?i)\\bINV[-_/ ]?\\d{4}[-_/ ]?\\d{3,6}\\b");

    /** "ABC TRD P LTD" and "ABC Traders Pvt Ltd" both become "abc traders". */
    public static String normalizeName(String raw) {
        String s = raw.toLowerCase().replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
        List<String> tokens = new ArrayList<>();
        for (String t : s.split(" ")) {
            if (t.isEmpty() || LEGAL_SUFFIXES.contains(t)) continue;
            tokens.add(ABBREVIATIONS.getOrDefault(t, t));
        }
        return String.join(" ", tokens);
    }

    /** Case/punctuation-insensitive key used for exact alias lookups. */
    public static String aliasKey(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9@]", "");
    }

    public static PayerKind payerKind(String payer) {
        if (payer.contains("@")) return PHONE.matcher(payer.substring(0, payer.indexOf('@'))).find() ? PayerKind.PHONE_VPA : PayerKind.VPA;
        if (MASK.matcher(payer).matches()) return PayerKind.MASK;
        return PayerKind.NAME;
    }

    public static String phone(String payer) {
        Matcher m = PHONE.matcher(payer);
        return m.find() ? m.group(1) : "";
    }

    public static String maskLast4(String payer) {
        Matcher m = MASK.matcher(payer);
        return m.matches() && m.group(1).length() == 4 ? m.group(1) : "";
    }

    /** Invoice numbers found in free text, normalised to letters+digits only (e.g. INV202600012). */
    public static List<String> invoiceRefs(String narration) {
        List<String> out = new ArrayList<>();
        Matcher m = INVOICE_REF.matcher(narration);
        while (m.find()) out.add(refKey(m.group()));
        return out;
    }

    public static String refKey(String id) {
        return id.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}

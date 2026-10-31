package com.reconcile.engine;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** String similarity measures used for payer-name matching. All return values in [0, 1]. */
public final class Similarity {
    private Similarity() {}

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    public static double levRatio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1.0 : 1.0 - (double) levenshtein(a, b) / max;
    }

    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;
        int window = Math.max(0, Math.max(s1.length(), s2.length()) / 2 - 1);
        boolean[] m1 = new boolean[s1.length()];
        boolean[] m2 = new boolean[s2.length()];
        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            for (int j = Math.max(0, i - window); j < Math.min(s2.length(), i + window + 1); j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) { m1[i] = m2[j] = true; matches++; break; }
            }
        }
        if (matches == 0) return 0.0;
        int transpositions = 0, k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        double m = matches;
        double jaro = (m / s1.length() + m / s2.length() + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(s1.length(), s2.length())) && s1.charAt(prefix) == s2.charAt(prefix)) prefix++;
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /** Order-insensitive similarity: compares shared tokens plus each side's leftovers (RapidFuzz-style token_set_ratio). */
    public static double tokenSet(String a, String b) {
        Set<String> ta = new TreeSet<>(Arrays.asList(a.split(" ")));
        Set<String> tb = new TreeSet<>(Arrays.asList(b.split(" ")));
        ta.remove(""); tb.remove("");
        if (ta.isEmpty() || tb.isEmpty()) return ta.equals(tb) ? 1.0 : 0.0;
        Set<String> common = new TreeSet<>(ta); common.retainAll(tb);
        Set<String> onlyA = new TreeSet<>(ta); onlyA.removeAll(tb);
        Set<String> onlyB = new TreeSet<>(tb); onlyB.removeAll(ta);
        String c = String.join(" ", common);
        String x = (c + " " + String.join(" ", onlyA)).trim();
        String y = (c + " " + String.join(" ", onlyB)).trim();
        if (common.isEmpty()) return levRatio(x, y);
        return Math.max(levRatio(c, x), Math.max(levRatio(c, y), levRatio(x, y)));
    }

    /** Gate used for identity decisions. Deliberately not Jaro-Winkler, which over-rewards shared prefixes. */
    public static double nameSim(String a, String b) {
        return Math.max(levRatio(a, b), tokenSet(a, b));
    }
}

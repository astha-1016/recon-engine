package com.reconcile.engine;

import com.reconcile.engine.Model.BankTxn;
import com.reconcile.engine.Model.Direction;

import java.util.*;

/**
 * Finds bank rows that repeat an earlier row (the same statement line imported twice, or a bank that lists a
 * credit twice). A duplicate must never be reconciled, or the same money would settle two invoices.
 *
 * A credit is a duplicate of an earlier one when the amounts are equal and either the bank reference is the
 * same, or (when there is no reference) date, payer, channel and narration are all the same.
 */
public final class DuplicateDetector {
    private DuplicateDetector() {}

    /** duplicate transaction id -> id of the row it repeats (the earliest by date, then id) */
    public static Map<String, String> find(List<BankTxn> txns) {
        List<BankTxn> credits = new ArrayList<>();
        for (BankTxn t : txns) if (t.direction() == Direction.CR) credits.add(t);
        credits.sort(Comparator.comparing(BankTxn::date).thenComparing(BankTxn::id));
        Map<String, String> firstSeen = new HashMap<>();
        Map<String, String> duplicates = new LinkedHashMap<>();
        for (BankTxn t : credits) {
            String key = key(t);
            String original = firstSeen.putIfAbsent(key, t.id());
            if (original != null) duplicates.put(t.id(), original);
        }
        return duplicates;
    }

    private static String key(BankTxn t) {
        if (!t.bankRef().isBlank()) return "REF|" + t.bankRef().trim().toUpperCase() + "|" + t.abs();
        return "ROW|" + t.date() + "|" + t.abs() + "|" + TextNorm.aliasKey(t.payer()) + "|" + t.channel().toUpperCase() + "|"
                + t.narration().toUpperCase().replaceAll("\\s+", " ").trim();
    }
}

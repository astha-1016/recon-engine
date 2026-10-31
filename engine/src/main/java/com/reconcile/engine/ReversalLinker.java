package com.reconcile.engine;

import com.reconcile.engine.Model.*;

import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pairs refunds, bank reversals and cheque returns with the original credit.
 * Uses the quoted reference when there is one; otherwise infers the original from amount, date and payer.
 */
public final class ReversalLinker {
    private ReversalLinker() {}

    public record Link(String debitId, String creditId, double confidence, String method) {}

    public static Map<String, Link> link(List<BankTxn> txns, Config cfg) {
        List<BankTxn> credits = new ArrayList<>();
        List<BankTxn> debits = new ArrayList<>();
        Map<String, BankTxn> byRef = new HashMap<>();
        for (BankTxn t : txns) {
            if (t.direction() == Direction.CR) {
                credits.add(t);
                if (!t.bankRef().isBlank()) byRef.put(t.bankRef(), t);
                byRef.put(t.id(), t);
            } else {
                TxnKind k = Classifier.kind(t);
                if (k != TxnKind.BANK_FEE) debits.add(t);
            }
        }
        debits.sort(Comparator.comparing(BankTxn::date).thenComparing(BankTxn::id));
        Map<String, Long> reversed = new HashMap<>();
        Map<String, Link> links = new LinkedHashMap<>();

        for (BankTxn d : debits) {
            Link link = byReference(d, byRef, reversed);
            if (link == null) link = infer(d, credits, reversed, cfg);
            if (link != null) {
                links.put(d.id(), link);
                reversed.merge(link.creditId(), d.abs(), Long::sum);
            }
        }
        return links;
    }

    private static Link byReference(BankTxn d, Map<String, BankTxn> byRef, Map<String, Long> reversed) {
        Set<BankTxn> hits = new LinkedHashSet<>();
        if (!d.reversalRef().isBlank() && byRef.containsKey(d.reversalRef())) hits.add(byRef.get(d.reversalRef()));
        for (String token : d.narration().split("[^A-Za-z0-9]+")) if (byRef.containsKey(token)) hits.add(byRef.get(token));
        if (hits.size() != 1) return null;
        BankTxn c = hits.iterator().next();
        if (c.abs() - reversed.getOrDefault(c.id(), 0L) < d.abs()) return null;
        return new Link(d.id(), c.id(), 1.0, "REFERENCE");
    }

    private static Link infer(BankTxn d, List<BankTxn> credits, Map<String, Long> reversed, Config cfg) {
        record Scored(BankTxn credit, double score, boolean full) {}
        List<Scored> scored = new ArrayList<>();
        String dKey = TextNorm.aliasKey(d.payer());
        for (BankTxn c : credits) {
            long days = ChronoUnit.DAYS.between(c.date(), d.date());
            long reversible = c.abs() - reversed.getOrDefault(c.id(), 0L);
            if (days < 0 || days > cfg.reversalWindowDays || reversible < d.abs()) continue;
            boolean full = reversible == d.abs() && c.abs() == d.abs();
            double payer = TextNorm.aliasKey(c.payer()).equals(dKey) ? 1.0
                    : (TextNorm.payerKind(c.payer()) == PayerKind.NAME && TextNorm.payerKind(d.payer()) == PayerKind.NAME)
                    ? Similarity.nameSim(TextNorm.normalizeName(c.payer()), TextNorm.normalizeName(d.payer())) : 0.3;
            if (!full && payer < 0.85) continue;
            double score = (full ? 0.6 : 0.4) + 0.25 * payer + 0.15 * (1.0 - days / (double) cfg.reversalWindowDays);
            scored.add(new Scored(c, score, full));
        }
        if (scored.isEmpty()) return null;
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        Scored best = scored.get(0);
        if (scored.size() > 1 && best.score - scored.get(1).score < 0.10) return null;
        return new Link(d.id(), best.credit.id(), best.full ? 0.95 : 0.85, "INFERRED");
    }
}

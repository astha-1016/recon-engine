package com.reconcile.engine;

import com.reconcile.engine.Model.*;

import java.util.*;

/** Scores engine output against ground truth. The engine itself never sees the truth. */
public final class Evaluator {
    private Evaluator() {}

    public static final class Counts {
        public int expected, predicted, correct;
        public double precision() { return predicted == 0 ? 1.0 : correct / (double) predicted; }
        public double recall() { return expected == 0 ? 0.0 : correct / (double) expected; }
    }

    public static final class Report {
        public String label = "";
        public String split = "";
        public Counts overall = new Counts();
        public final Map<String, Counts> byScenario = new TreeMap<>();
        public final Map<String, Counts> byChannel = new TreeMap<>();
        public int invoiceTxns, autoTxns, reviewTxns, unmatchedTxns, manualTxns;
        public int autoTxnsFullyCorrect;
        /** Of the payments sent to review, how many had the right plan as the top suggestion (one click for a person). */
        public int reviewTop1Correct;
        public int feeTxns, feesLeftAlone;
        public int invoices, statusCorrect;

        public double reviewTop1Rate() { return reviewTxns == 0 ? 0 : reviewTop1Correct / (double) reviewTxns; }
        public double autoRate() { return invoiceTxns == 0 ? 0 : autoTxns / (double) invoiceTxns; }
        public double autoTxnPrecision() { return autoTxns == 0 ? 1 : autoTxnsFullyCorrect / (double) autoTxns; }
        /** Share of invoice payments that no longer need a person, versus 100% manual today. */
        public double effortReduction() { return invoiceTxns == 0 ? 0 : 1.0 - (reviewTxns + unmatchedTxns) / (double) invoiceTxns; }
        public double statusAccuracy() { return invoices == 0 ? 0 : statusCorrect / (double) invoices; }
    }

    public static Report evaluate(DataSet ds, Reconciler.Result res, String split, String label) {
        Report rep = new Report();
        rep.label = label;
        rep.split = split == null ? "all" : split;
        Map<String, String> txnSplit = ds.txnSplit();
        Map<String, BankTxn> txns = new HashMap<>();
        for (BankTxn t : ds.txns) txns.put(t.id(), t);

        // Two identical bank rows are interchangeable: money applied once, to either row, is correct.
        Map<String, String> dupOf = new HashMap<>();
        for (DataSet.Truth t : ds.truth) if (t.scenario().equals("duplicate_row") && !t.originalTxnId().isEmpty()) dupOf.put(t.txnId(), t.originalTxnId());
        Map<String, Long> expected = new HashMap<>();
        Map<String, String> scenarioOf = new HashMap<>();
        Map<String, Set<String>> truthTxnInvoices = new HashMap<>();
        for (DataSet.Truth t : ds.truth) {
            if (split != null && !split.equals(t.split())) continue;
            if (t.invoiceId().isEmpty()) {
                rep.feeTxns++;
                Outcome o = res.outcomes.get(t.txnId());
                String orig = dupOf.get(t.txnId());
                // a duplicate row is "left alone" if the pair of identical rows was applied at most once
                long applied = res.allocations.stream().filter(a -> a.txnId().equals(t.txnId()) || (orig != null && a.txnId().equals(orig))).map(Allocation::txnId).distinct().count();
                if (o != null && (orig == null ? applied == 0 : applied <= 1)) rep.feesLeftAlone++;
                continue;
            }
            String key = t.txnId() + "|" + t.invoiceId();
            expected.put(key, t.amount());
            scenarioOf.put(key, t.scenario());
            truthTxnInvoices.computeIfAbsent(t.txnId(), k -> new HashSet<>()).add(t.invoiceId());
            bump(rep.byScenario, t.scenario()).expected++;
            bump(rep.byChannel, channelOf(res, txns, t.txnId())).expected++;
            rep.overall.expected++;
        }

        Set<String> wrongTxns = new HashSet<>();
        for (Allocation a : res.allocations) {
            if (a.method() == Method.MANUAL) continue;
            String txnKey = dupOf.getOrDefault(a.txnId(), a.txnId());   // an allocation on a duplicate row counts for its original
            String ts = txnSplit.get(txnKey);
            if (ts == null || (split != null && !split.equals(ts))) continue;
            String key = txnKey + "|" + a.invoiceId();
            rep.overall.predicted++;
            String scenario = scenarioOf.getOrDefault(key, "unexpected");
            String channel = channelOf(res, txns, txnKey);
            bump(rep.byScenario, scenario).predicted++;
            bump(rep.byChannel, channel).predicted++;
            Long want = expected.get(key);
            if (want != null && want == a.amount()) {
                rep.overall.correct++;
                bump(rep.byScenario, scenario).correct++;
                bump(rep.byChannel, channel).correct++;
            } else wrongTxns.add(txnKey);
        }

        for (String txnId : truthTxnInvoices.keySet()) {
            Outcome o = res.outcomes.get(txnId);
            if (o == null) continue;
            if (o.kind == Model.TxnKind.DUPLICATE) {   // the engine kept the twin row and flagged this one: judge the pair
                String twin = o.linkedTxnId;
                Outcome to = res.outcomes.get(twin);
                if (to != null) o = to;
            }
            rep.invoiceTxns++;
            if (o.method == Method.MANUAL) rep.manualTxns++;
            else if (o.decision == Decision.AUTO) {
                rep.autoTxns++;
                if (!wrongTxns.contains(txnId)) rep.autoTxnsFullyCorrect++;
            } else if (o.decision == Decision.REVIEW) {
                rep.reviewTxns++;
                if (!o.top.isEmpty() && matchesTruth(o.top.get(0), truthTxnInvoices.get(txnId), expected, txnId)) rep.reviewTop1Correct++;
            }
            else rep.unmatchedTxns++;
        }
        scoreStatuses(ds, res, split, rep);
        return rep;
    }

    private static boolean matchesTruth(Model.Candidate c, Set<String> invoices, Map<String, Long> expected, String txnId) {
        if (invoices == null || c.parts.size() != invoices.size()) return false;
        for (Model.Part p : c.parts) {
            Long want = expected.get(txnId + "|" + p.invoiceId());
            if (want == null || want != p.amount()) return false;
        }
        return true;
    }

    private static String channelOf(Reconciler.Result res, Map<String, BankTxn> txns, String txnId) {
        Outcome o = res.outcomes.get(txnId);
        return o != null ? o.channel.name() : Classifier.channel(txns.get(txnId)).name();
    }

    private static Counts bump(Map<String, Counts> m, String k) { return m.computeIfAbsent(k, x -> new Counts()); }

    /** Expected final invoice state, derived from ground truth with the same rules as the Ledger state machine. */
    private static void scoreStatuses(DataSet ds, Reconciler.Result res, String split, Report rep) {
        Map<String, String> customerSplit = new HashMap<>();
        Map<String, Long> cash = new HashMap<>();
        Map<String, String> lastReversal = new HashMap<>();
        Set<String> shortPaid = new HashSet<>();
        for (DataSet.Truth t : ds.truth) {
            if (t.invoiceId().isEmpty()) continue;
            customerSplit.putIfAbsent(t.customerId(), t.split());
            cash.merge(t.invoiceId(), t.amount(), Long::sum);
            if (t.amount() < 0) lastReversal.put(t.invoiceId(), t.eventType());
            if ((t.scenario().equals("short_pay") || t.scenario().equals("fee_deducted"))) shortPaid.add(t.invoiceId());
        }
        for (Invoice inv : ds.invoices) {
            String cs = customerSplit.get(inv.customerId());
            if (cs == null || (split != null && !split.equals(cs))) continue;
            long c = cash.getOrDefault(inv.id(), 0L);
            InvoiceStatus want;
            String rev = lastReversal.get(inv.id());
            if (rev != null && rev.equals("CHEQUE_RETURN") && c <= 0) want = InvoiceStatus.OPEN;
            else if (rev != null && rev.equals("REFUND") && c <= 0) want = InvoiceStatus.REFUNDED;
            else if (rev != null) want = InvoiceStatus.DISPUTED;
            else if (shortPaid.contains(inv.id()) || c >= inv.amount()) want = InvoiceStatus.PAID;
            else want = c > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.OPEN;
            rep.invoices++;
            if (res.ledger.status(res.ledger.get(inv.id())) == want) rep.statusCorrect++;
        }
    }

    public static String pct(double v) { return String.format("%.1f%%", v * 100); }

    public static String render(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("== %s | split=%s ==%n", r.label, r.split));
        sb.append(String.format("Allocation precision %s  recall %s  (correct %d / predicted %d / expected %d)%n",
                pct(r.overall.precision()), pct(r.overall.recall()), r.overall.correct, r.overall.predicted, r.overall.expected));
        sb.append(String.format("Invoice payments: %d | auto %d (%s, fully-correct %s) | review %d | unmatched %d | manual %d%n",
                r.invoiceTxns, r.autoTxns, pct(r.autoRate()), pct(r.autoTxnPrecision()), r.reviewTxns, r.unmatchedTxns, r.manualTxns));
        sb.append(String.format("Review queue: top suggestion is the right answer for %d of %d items (%s), so most need only a click%n",
                r.reviewTop1Correct, r.reviewTxns, pct(r.reviewTop1Rate())));
        sb.append(String.format("Manual effort reduced from 100%% to %s | non-invoice rows (bank fees, duplicates) left alone %d/%d | final invoice status accuracy %s (%d invoices)%n",
                pct(1 - r.effortReduction()), r.feesLeftAlone, r.feeTxns, pct(r.statusAccuracy()), r.invoices));
        sb.append(String.format("%-24s %9s %9s %9s %9s%n", "by scenario", "expected", "predicted", "precision", "recall"));
        r.byScenario.forEach((k, c) -> sb.append(String.format("%-24s %9d %9d %9s %9s%n", k, c.expected, c.predicted, pct(c.precision()), pct(c.recall()))));
        sb.append(String.format("%-24s %9s %9s %9s %9s%n", "by channel", "expected", "predicted", "precision", "recall"));
        r.byChannel.forEach((k, c) -> sb.append(String.format("%-24s %9d %9d %9s %9s%n", k, c.expected, c.predicted, pct(c.precision()), pct(c.recall()))));
        return sb.toString();
    }
}

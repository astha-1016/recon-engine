package com.reconcile.engine;

import com.reconcile.engine.IdentityResolver.Identity;
import com.reconcile.engine.Model.*;

import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds the possible explanations ("plans") for one incoming payment and a feature vector for each.
 * Blocking keeps this cheap: only invoices of plausible customers, or whose balance is consistent with the
 * amount, are considered. Composite (many-invoices-to-one-payment) search is a bounded subset-sum.
 */
final class CandidateGenerator {

    /** Simple instalment fractions (num/den) that signal a deliberate partial payment. */
    private static final long[][] FRACTIONS = {{1, 2}, {1, 3}, {2, 3}, {1, 4}, {3, 4}, {1, 5}, {2, 5}, {3, 5}, {4, 5}, {1, 10}};
    private static final int MAX_COMPOSITES_PER_CUSTOMER = 20;
    private static final long FRACTION_TOLERANCE_PAISE = 5;

    private record Plan(List<Ledger.Entry> entries, List<Long> allocs, long adjustment, long excess,
                        String customerId, boolean refHit) {
        String key() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) sb.append(entries.get(i).inv.id()).append(':').append(allocs.get(i)).append(';');
            return sb.toString();
        }
    }

    private final Config cfg;
    private final Ledger ledger;
    private final IdentityResolver resolver;

    CandidateGenerator(Config cfg, Ledger ledger, IdentityResolver resolver) {
        this.cfg = cfg;
        this.ledger = ledger;
        this.resolver = resolver;
    }

    List<Candidate> generate(BankTxn t, Channel channel, Identity id, List<Ledger.Entry> refs) {
        long amt = t.abs();
        int window = Classifier.windowDays(channel, cfg);
        Set<String> refIds = new HashSet<>();
        for (Ledger.Entry r : refs) refIds.add(r.inv.id());

        Map<String, Ledger.Entry> pool = new LinkedHashMap<>();
        for (String cid : id.scores.keySet())
            for (Ledger.Entry e : ledger.ofCustomer(cid)) if (eligible(e, t, window)) pool.put(e.inv.id(), e);
        for (Ledger.Entry e : ledger.all())
            if (!pool.containsKey(e.inv.id()) && eligible(e, t, window) && amountConsistent(amt, e)) pool.put(e.inv.id(), e);
        for (Ledger.Entry r : refs) if (ledger.outstanding(r) > 0) pool.put(r.inv.id(), r);

        Map<String, Plan> plans = new LinkedHashMap<>();
        for (Ledger.Entry e : pool.values()) {
            Plan p = single(e, amt, refIds.contains(e.inv.id()));
            if (p != null) plans.putIfAbsent(p.key(), p);
        }
        if (!refs.isEmpty()) {
            Plan p = referencePlan(refs, amt);
            if (p != null) plans.putIfAbsent(p.key(), p);
        }
        if (cfg.enableComposite && amt > 0) {
            Collection<String> customers = id.scores.isEmpty() ? ledger.customerIds() : id.scores.keySet();
            for (String cid : customers) composites(cid, amt, window, t, refIds, plans);
        }
        return build(new ArrayList<>(plans.values()), t, channel, id);
    }

    private boolean eligible(Ledger.Entry e, BankTxn t, int window) {
        if (ledger.outstanding(e) <= 0 || e.inv.issueDate().isAfter(t.date())) return false;
        return ChronoUnit.DAYS.between(e.inv.issueDate(), t.date()) <= window;
    }

    private boolean amountConsistent(long amt, Ledger.Entry e) {
        long out = ledger.outstanding(e);
        if (amt == out) return true;
        if (amt < out && out - amt <= cfg.shortPayTolerance * out) return true;
        if (amt > out && amt - out <= cfg.overPayTolerance * out) return true;
        return fractionError(amt, e.inv.amount(), out) <= FRACTION_TOLERANCE_PAISE;
    }

    /** Smallest distance (paise) between the payment and a simple fraction of the invoice total or balance. */
    static double fractionError(long amt, long total, long out) {
        double best = Double.MAX_VALUE;
        for (long base : new long[]{total, out})
            for (long[] f : FRACTIONS) best = Math.min(best, Math.abs(amt * f[1] - base * f[0]) / (double) f[1]);
        return best;
    }

    private Plan single(Ledger.Entry e, long amt, boolean refHit) {
        long out = ledger.outstanding(e);
        if (out <= 0) return null;
        if (amt >= out) {
            long excess = amt - out;
            if (excess > cfg.overPayTolerance * out && !refHit) return null;
            return new Plan(List.of(e), List.of(out), 0, excess, e.inv.customerId(), refHit);
        }
        long shortfall = out - amt;
        long adj = shortfall <= cfg.shortPayTolerance * out ? shortfall : 0;
        return new Plan(List.of(e), List.of(amt), adj, 0, e.inv.customerId(), refHit);
    }

    /** The payer quoted invoice numbers: fill them oldest-due first, any surplus becomes a credit. */
    private Plan referencePlan(List<Ledger.Entry> refs, long amt) {
        List<Ledger.Entry> sorted = new ArrayList<>(refs);
        sorted.sort(Comparator.comparing((Ledger.Entry e) -> e.inv.dueDate()).thenComparing(e -> e.inv.id()));
        List<Ledger.Entry> used = new ArrayList<>();
        List<Long> allocs = new ArrayList<>();
        long left = amt;
        for (Ledger.Entry e : sorted) {
            long out = ledger.outstanding(e);
            if (out <= 0 || left <= 0) continue;
            long take = Math.min(left, out);
            used.add(e);
            allocs.add(take);
            left -= take;
        }
        if (used.isEmpty()) return null;
        return new Plan(used, allocs, 0, left, used.get(0).inv.customerId(), true);
    }

    private void composites(String customerId, long amt, int window, BankTxn t, Set<String> refIds, Map<String, Plan> plans) {
        List<Ledger.Entry> eligible = new ArrayList<>();
        for (Ledger.Entry e : ledger.ofCustomer(customerId)) if (eligible(e, t, window)) eligible.add(e);
        if (eligible.size() < 2) return;
        eligible.sort(Comparator.comparing((Ledger.Entry e) -> e.inv.dueDate()).thenComparing(e -> e.inv.id()));
        if (eligible.size() > cfg.maxInvoicesPerSubset) eligible = eligible.subList(0, cfg.maxInvoicesPerSubset);
        int[] found = {0};
        dfs(eligible, 0, 0, new ArrayList<>(), amt, customerId, refIds, plans, found);
    }

    private void dfs(List<Ledger.Entry> items, int start, long sum, List<Ledger.Entry> chosen, long target,
                     String customerId, Set<String> refIds, Map<String, Plan> plans, int[] found) {
        for (int k = start; k < items.size() && found[0] < MAX_COMPOSITES_PER_CUSTOMER; k++) {
            Ledger.Entry e = items.get(k);
            long next = sum + ledger.outstanding(e);
            if (next > target) continue;
            chosen.add(e);
            if (next == target) {
                if (chosen.size() >= 2) {
                    List<Long> allocs = new ArrayList<>();
                    boolean allRef = true;
                    for (Ledger.Entry c : chosen) { allocs.add(ledger.outstanding(c)); allRef &= refIds.contains(c.inv.id()); }
                    Plan p = new Plan(List.copyOf(chosen), allocs, 0, 0, customerId, allRef);
                    if (plans.putIfAbsent(p.key(), p) == null) found[0]++;
                }
            } else if (chosen.size() < cfg.maxSubsetSize) {
                dfs(items, k + 1, next, chosen, target, customerId, refIds, plans, found);
            }
            chosen.remove(chosen.size() - 1);
        }
    }

    // ---------------------------------------------------------------- features

    private List<Candidate> build(List<Plan> plans, BankTxn t, Channel channel, Identity id) {
        long amt = t.abs();
        int n = plans.size();
        boolean[] exactFlag = new boolean[n];
        boolean[] fracFlag = new boolean[n];
        boolean[] looseFlag = new boolean[n];
        int exactCount = 0, fracCount = 0, looseCount = 0;
        for (int i = 0; i < n; i++) {
            Plan p = plans.get(i);
            long sumOut = sumOut(p);
            exactFlag[i] = amt == sumOut;
            fracFlag[i] = p.entries.size() == 1 && !exactFlag[i]
                    && fractionError(amt, p.entries.get(0).inv.amount(), sumOut) <= FRACTION_TOLERANCE_PAISE;
            looseFlag[i] = exactFlag[i] || fracFlag[i] || p.adjustment > 0 || (p.excess > 0 && p.excess <= cfg.overPayTolerance * sumOut);
            if (exactFlag[i]) exactCount++;
            if (fracFlag[i]) fracCount++;
            if (looseFlag[i]) looseCount++;
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Plan p = plans.get(i);
            double[] f = features(p, t, channel, id, n);
            Features.put(f, "n_amount_consistent", looseCount);
            Features.put(f, "amount_unique", looseFlag[i] && looseCount == 1 ? 1 : 0);
            Features.put(f, "n_exact_candidates", exactCount);
            Features.put(f, "exact_unique", exactFlag[i] && exactCount == 1 ? 1 : 0);
            Features.put(f, "n_frac_candidates", fracCount);
            Features.put(f, "frac_unique", fracFlag[i] && fracCount == 1 ? 1 : 0);
            List<Part> parts = new ArrayList<>();
            for (int k = 0; k < p.entries.size(); k++) parts.add(new Part(p.entries.get(k).inv.id(), p.allocs.get(k)));
            out.add(new Candidate(parts, p.adjustment, p.excess, p.customerId, f, p.refHit));
        }
        return out;
    }

    private long sumOut(Plan p) {
        long s = 0;
        for (Ledger.Entry e : p.entries) s += ledger.outstanding(e);
        return s;
    }

    private double[] features(Plan p, BankTxn t, Channel channel, Identity id, int poolSize) {
        double[] f = new double[Features.NAMES.size()];
        long amt = t.abs();
        long sumOut = sumOut(p);
        long sumOrig = 0, cash = 0;
        int payments = 0;
        var latestIssue = p.entries.get(0).inv.issueDate();
        var earliestDue = p.entries.get(0).inv.dueDate();
        for (Ledger.Entry e : p.entries) {
            sumOrig += e.inv.amount();
            cash += e.cash;
            payments += e.payments;
            if (e.inv.issueDate().isAfter(latestIssue)) latestIssue = e.inv.issueDate();
            if (e.inv.dueDate().isBefore(earliestDue)) earliestDue = e.inv.dueDate();
        }
        boolean exact = amt == sumOut;
        double fracErr = p.entries.size() == 1 ? fractionError(amt, sumOrig, sumOut) : 0;
        Features.put(f, "n_inv", p.entries.size());
        Features.put(f, "is_composite", p.entries.size() > 1 ? 1 : 0);
        Features.put(f, "amount_exact", exact ? 1 : 0);
        Features.put(f, "amount_diff_abs_log", Math.log1p(Math.abs(amt - sumOut) / 100.0));
        Features.put(f, "amount_diff_pct", sumOut == 0 ? 1 : Math.abs(amt - sumOut) / (double) sumOut);
        Features.put(f, "ratio_out", sumOut == 0 ? 0 : amt / (double) sumOut);
        Features.put(f, "ratio_orig", sumOrig == 0 ? 0 : amt / (double) sumOrig);
        Features.put(f, "frac_err_log", Math.log1p(fracErr));
        Features.put(f, "frac_match", p.entries.size() == 1 && !exact && fracErr <= FRACTION_TOLERANCE_PAISE ? 1 : 0);

        String cid = p.customerId;
        Features.put(f, "identity_score", id.scores.getOrDefault(cid, 0.0));
        Features.put(f, "alias_hit", id.aliasHits.contains(cid) ? 1 : 0);
        Features.put(f, "n_identity_customers", id.scores.size());
        double[] nf = resolver.nameFeatures(id, cid);
        Features.put(f, "name_lev", nf[0]);
        Features.put(f, "name_jw", nf[1]);
        Features.put(f, "name_token", nf[2]);
        Features.put(f, "kind_name", id.kind == PayerKind.NAME ? 1 : 0);
        Features.put(f, "kind_vpa", id.kind == PayerKind.VPA ? 1 : 0);
        Features.put(f, "kind_phone", id.kind == PayerKind.PHONE_VPA ? 1 : 0);
        Features.put(f, "kind_mask", id.kind == PayerKind.MASK ? 1 : 0);
        Features.put(f, "phone_match", id.phoneHits.contains(cid) ? 1 : 0);
        Features.put(f, "last4_match", id.last4Hits.contains(cid) ? 1 : 0);

        switch (channel) {
            case QR -> Features.put(f, "ch_qr", 1);
            case UPI_ID -> Features.put(f, "ch_upi", 1);
            case NEFT -> Features.put(f, "ch_neft", 1);
            case IMPS, RTGS -> Features.put(f, "ch_imps", 1);
            case CHEQUE -> Features.put(f, "ch_cheque", 1);
            case CASH -> Features.put(f, "ch_cash", 1);
            default -> Features.put(f, "ch_other", 1);
        }
        Features.put(f, "gap_days", ChronoUnit.DAYS.between(latestIssue, t.date()));
        Features.put(f, "days_vs_due", ChronoUnit.DAYS.between(earliestDue, t.date()));
        Features.put(f, "already_paid_frac", sumOrig == 0 ? 0 : cash / (double) sumOrig);
        Features.put(f, "prior_payments", payments);
        Features.put(f, "from_global", id.scores.containsKey(cid) ? 0 : 1);
        Features.put(f, "ref_hit", p.refHit ? 1 : 0);
        Features.put(f, "fifo_rank", p.entries.size() == 1 ? fifoRank(p.entries.get(0)) : 0);
        Features.put(f, "short_pay", p.adjustment > 0 ? 1 : 0);
        Features.put(f, "over_pay", p.excess > 0 ? 1 : 0);
        Features.put(f, "pool_size_log", Math.log1p(poolSize));
        return f;
    }

    private int fifoRank(Ledger.Entry e) {
        int rank = 0;
        for (Ledger.Entry o : ledger.ofCustomer(e.inv.customerId()))
            if (o != e && ledger.outstanding(o) > 0 && o.inv.dueDate().isBefore(e.inv.dueDate())) rank++;
        return rank;
    }
}

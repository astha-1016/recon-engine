package com.reconcile.engine;

import com.reconcile.engine.IdentityResolver.Identity;
import com.reconcile.engine.Model.*;
import com.reconcile.engine.ReversalLinker.Link;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The reconciliation pipeline. For every bank transaction, in date order:
 * classify -> (debits: link to the original and reverse) -> resolve payer -> generate candidates ->
 * Tier 1 exact reference match -> Tier 2 fuzzy (ML-scored) -> Tier 3 composite -> allocate or route to review.
 * Manual decisions are always honoured, and aliases learned from them are used on the whole run.
 */
public final class Reconciler {

    public record Input(List<Customer> customers, List<Invoice> invoices, List<BankTxn> txns,
                        List<Alias> aliases, List<ManualDecision> manual, List<CreditApplication> creditApplications) {
        public Input(List<Customer> customers, List<Invoice> invoices, List<BankTxn> txns,
                     List<Alias> aliases, List<ManualDecision> manual) {
            this(customers, invoices, txns, aliases, manual, List.of());
        }
    }

    public static final class Result {
        public final List<Allocation> allocations = new ArrayList<>();
        public final List<Adjustment> adjustments = new ArrayList<>();
        public final List<Credit> credits = new ArrayList<>();
        public final Map<String, Outcome> outcomes = new LinkedHashMap<>();
        public final List<Alias> learnedAliases = new ArrayList<>();
        /** Things a person asked for that could not be applied (e.g. a credit that no longer exists). */
        public final List<String> warnings = new ArrayList<>();
        public Ledger ledger;
        public String scorerVersion = "";
    }

    public interface CandidateSink { void accept(BankTxn txn, List<Candidate> candidates); }

    private final Config cfg;
    private final Scorer scorer;
    private CandidateSink sink;

    public Reconciler(Config cfg, Scorer scorer) {
        this.cfg = cfg;
        this.scorer = scorer;
    }

    public Reconciler withSink(CandidateSink sink) {
        this.sink = sink;
        return this;
    }

    public Result run(Input in) {
        Result res = new Result();
        Ledger ledger = new Ledger(in.invoices());
        res.ledger = ledger;

        List<Customer> customers = in.customers().isEmpty() ? deriveCustomers(in.invoices()) : in.customers();
        Map<String, BankTxn> txnById = new HashMap<>();
        for (BankTxn t : in.txns()) txnById.put(t.id(), t);
        Map<String, ManualDecision> manual = new HashMap<>();
        for (ManualDecision m : in.manual()) manual.put(m.txnId(), m);

        List<Alias> aliases = new ArrayList<>(in.aliases());
        for (ManualDecision m : in.manual()) {
            BankTxn t = txnById.get(m.txnId());
            if (t == null || t.direction() != Direction.CR) continue;
            Set<String> owners = new HashSet<>();
            for (Part p : m.parts()) if (ledger.get(p.invoiceId()) != null) owners.add(ledger.get(p.invoiceId()).inv.customerId());
            if (owners.size() == 1) {
                Alias a = new Alias(t.payer(), owners.iterator().next());
                aliases.add(a);
                res.learnedAliases.add(a);
            }
        }

        IdentityResolver resolver = new IdentityResolver(customers, aliases, cfg.identityThreshold);
        Map<String, String> duplicates = DuplicateDetector.find(in.txns());
        List<BankTxn> distinct = new ArrayList<>();
        for (BankTxn t : in.txns()) if (!duplicates.containsKey(t.id())) distinct.add(t);
        CandidateGenerator generator = new CandidateGenerator(cfg, ledger, resolver);
        Map<String, Ledger.Entry> refIndex = new HashMap<>();
        for (Ledger.Entry e : ledger.all()) refIndex.put(TextNorm.refKey(e.inv.id()), e);
        Map<String, Link> links = ReversalLinker.link(distinct, cfg);
        Map<String, Long> reversedBy = new HashMap<>();

        List<BankTxn> ordered = new ArrayList<>(in.txns());
        ordered.sort(Comparator.comparing(BankTxn::date)
                .thenComparing((BankTxn t) -> t.direction() == Direction.CR ? 0 : 1).thenComparing(BankTxn::id));

        java.util.function.Consumer<BankTxn> handle = t -> {
            Outcome o = new Outcome(t.id());
            o.kind = Classifier.kind(t);
            o.channel = Classifier.channel(t);
            res.outcomes.put(t.id(), o);
            try {
                ManualDecision md = manual.get(t.id());
                if (md != null) applyManual(t, md, o, res, ledger, reversedBy);
                else if (duplicates.containsKey(t.id())) {
                    o.kind = TxnKind.DUPLICATE;
                    o.decision = Decision.CLASSIFIED;
                    o.linkedTxnId = duplicates.get(t.id());
                    o.reason = "Duplicate of " + duplicates.get(t.id()) + " (same amount and bank reference): not reconciled";
                } else if (o.kind == TxnKind.BANK_FEE) { o.decision = Decision.CLASSIFIED; o.reason = "Bank fee: not an invoice payment"; }
                else if (t.direction() == Direction.DR) reverse(t, links.get(t.id()), o, res, ledger, reversedBy);
                else pay(t, o, res, ledger, resolver, generator, refIndex);
            } catch (Exception e) {
                throw new IllegalStateException("Failed on transaction " + t.id() + ": " + e.getMessage(), e);
            }
        };

        for (BankTxn t : ordered) handle.accept(t);
        for (int round = 1; round <= cfg.resolutionRounds; round++) {
            int progress = 0;
            for (BankTxn t : ordered) {
                Outcome prev = res.outcomes.get(t.id());
                boolean waiting = prev.decision == Decision.REVIEW || prev.decision == Decision.UNMATCHED;
                if (!waiting || prev.method == Method.REVERSAL || manual.containsKey(t.id())) continue;
                handle.accept(t);
                Outcome now = res.outcomes.get(t.id());
                if (now.decision == Decision.AUTO) {
                    progress++;
                    now.reason = (now.reason == null || now.reason.isBlank() ? "" : now.reason + "; ") + "resolved in round " + round + " once other payments were matched";
                }
            }
            if (progress == 0) break;
        }
        applyCredits(in.creditApplications(), res, ledger);
        res.scorerVersion = scorer.version();   // read after scoring: the ML version is known only once the service has answered
        return res;
    }

    /** A person moved part of a payment's surplus onto another invoice. */
    private void applyCredits(List<CreditApplication> apps, Result res, Ledger ledger) {
        for (CreditApplication a : apps) {
            int idx = -1;
            for (int i = 0; i < res.credits.size(); i++) if (res.credits.get(i).txnId().equals(a.txnId())) idx = i;
            Ledger.Entry inv = ledger.get(a.invoiceId());
            if (idx < 0) { res.warnings.add("Credit from " + a.txnId() + " no longer exists; application to " + a.invoiceId() + " skipped"); continue; }
            Credit c = res.credits.get(idx);
            if (inv == null) { res.warnings.add("Unknown invoice " + a.invoiceId()); continue; }
            if (a.amount() <= 0 || a.amount() > c.amount()) { res.warnings.add("Credit from " + a.txnId() + " has only " + Money.format(c.amount()) + " left; cannot apply " + Money.format(a.amount())); continue; }
            if (a.amount() > ledger.outstanding(inv)) { res.warnings.add("Invoice " + a.invoiceId() + " only owes " + Money.format(ledger.outstanding(inv)) + "; cannot apply " + Money.format(a.amount())); continue; }
            ledger.pay(a.invoiceId(), a.amount());
            res.allocations.add(new Allocation(a.txnId(), a.invoiceId(), a.amount(), Method.MANUAL, 1.0));
            if (c.amount() == a.amount()) res.credits.remove(idx);
            else res.credits.set(idx, new Credit(c.txnId(), c.customerId(), c.amount() - a.amount()));
        }
    }

    // ------------------------------------------------------------ payments

    private void pay(BankTxn t, Outcome o, Result res, Ledger ledger, IdentityResolver resolver,
                     CandidateGenerator generator, Map<String, Ledger.Entry> refIndex) throws Exception {
        Identity id = resolver.resolve(t.payer());
        List<Ledger.Entry> refs = new ArrayList<>();
        for (String key : TextNorm.invoiceRefs(t.narration())) {
            Ledger.Entry e = refIndex.get(key);
            if (e != null && !refs.contains(e) && ledger.outstanding(e) > 0) refs.add(e);
        }
        List<Candidate> cands = generator.generate(t, o.channel, id, refs);

        // Tier 1: invoice reference quoted AND amount equals the balance exactly -> deterministic, confidence 1.0
        for (Candidate c : cands) {
            if (c.refHit && c.features[Features.AMOUNT_EXACT] == 1 && c.excess == 0 && c.adjustment == 0) {
                c.score = 1.0;
                o.top = List.of(c);
                apply(t, c, Method.EXACT, 1.0, o, res, ledger);
                o.reason = "Invoice reference and exact amount";
                return;
            }
        }
        if (sink != null) sink.accept(t, cands);   // only payments that reach the fuzzy/ML tier are training rows
        if (!cfg.enableFuzzy) { o.reason = "No exact match (fuzzy tier disabled)"; return; }
        if (cands.isEmpty()) { o.reason = "No candidate invoices for this payment"; return; }

        List<double[]> rows = new ArrayList<>();
        for (Candidate c : cands) rows.add(c.features);
        double[] scores = scorer.score(rows);
        for (int i = 0; i < cands.size(); i++) cands.get(i).score = scores[i];
        cands.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed().thenComparing(Candidate::key));
        o.top = new ArrayList<>(cands.subList(0, Math.min(3, cands.size())));

        Candidate best = cands.get(0);
        boolean unidentified = cfg.requireIdentityForAuto && best.features[Features.IDENTITY] == 0 && !best.refHit;
        List<Candidate> near = cands.stream().filter(c -> c != best && best.score - c.score < cfg.margin).collect(Collectors.toList());
        if (best.score >= cfg.autoThreshold && unidentified) {
            o.decision = Decision.REVIEW;
            o.confidence = best.score;
            o.reason = "Matched on amount alone and the payer is not identified (strict policy): please confirm";
        } else if (best.score >= cfg.autoThreshold) {
            if (near.isEmpty()) {
                apply(t, best, best.parts.size() > 1 ? Method.COMPOSITE : Method.FUZZY, best.score, o, res, ledger);
            } else if (near.stream().allMatch(c -> interchangeable(best, c))) {
                List<Candidate> group = new ArrayList<>(near);
                group.add(best);
                Candidate pick = group.stream().min(Comparator
                        .comparing((Candidate c) -> ledger.get(c.parts.get(0).invoiceId()).inv.dueDate())
                        .thenComparing(c -> c.parts.get(0).invoiceId())).get();
                apply(t, pick, Method.FUZZY, best.score * 0.95, o, res, ledger);
                o.reason = "FIFO tie-break among interchangeable invoices of the same customer";
            } else {
                o.decision = Decision.REVIEW;
                o.confidence = best.score;
                o.reason = "Ambiguous: another explanation scores within " + cfg.margin;
            }
        } else if (best.score >= cfg.reviewThreshold) {
            o.decision = Decision.REVIEW;
            o.confidence = best.score;
            o.reason = "Plausible match below the auto threshold";
        } else {
            o.decision = Decision.UNMATCHED;
            o.confidence = best.score;
            o.reason = "No candidate is convincing enough";
        }
    }

    private boolean interchangeable(Candidate a, Candidate b) {
        return a.parts.size() == 1 && b.parts.size() == 1 && Objects.equals(a.customerId, b.customerId)
                && a.parts.get(0).amount() == b.parts.get(0).amount() && a.adjustment == b.adjustment && a.excess == b.excess;
    }

    private void apply(BankTxn t, Candidate c, Method method, double confidence, Outcome o, Result res, Ledger ledger) {
        for (Part p : c.parts) {
            ledger.pay(p.invoiceId(), p.amount());
            res.allocations.add(new Allocation(t.id(), p.invoiceId(), p.amount(), method, confidence));
        }
        if (c.adjustment > 0) {
            String last = c.parts.get(c.parts.size() - 1).invoiceId();
            ledger.adjust(last, c.adjustment);
            res.adjustments.add(new Adjustment(t.id(), last, c.adjustment, "SHORT_PAY"));
        }
        if (c.excess > 0) res.credits.add(new Credit(t.id(), c.customerId, c.excess));
        o.decision = Decision.AUTO;
        o.method = method;
        o.confidence = confidence;
    }

    // ------------------------------------------------------------ reversals

    private void reverse(BankTxn t, Link link, Outcome o, Result res, Ledger ledger, Map<String, Long> reversedBy) {
        if (link == null) { o.reason = "No original payment could be identified"; return; }
        o.linkedTxnId = link.creditId();
        List<Allocation> originals = res.allocations.stream()
                .filter(a -> a.txnId().equals(link.creditId()) && a.amount() > 0).collect(Collectors.toList());
        if (originals.isEmpty()) { o.reason = "Original payment " + link.creditId() + " is not reconciled yet"; return; }
        if (link.confidence() < cfg.autoThreshold) {
            o.decision = Decision.REVIEW;
            o.confidence = link.confidence();
            o.reason = "Original payment inferred as " + link.creditId() + "; please confirm";
            return;
        }
        TxnKind kind = o.kind == TxnKind.OTHER_DEBIT ? TxnKind.REVERSAL : o.kind;
        long left = t.abs();
        for (Allocation a : originals) {
            String key = link.creditId() + "|" + a.invoiceId();
            long take = Math.min(left, a.amount() - reversedBy.getOrDefault(key, 0L));
            if (take <= 0) continue;
            ledger.reverse(a.invoiceId(), take, kind);
            res.allocations.add(new Allocation(t.id(), a.invoiceId(), -take, Method.REVERSAL, link.confidence()));
            reversedBy.merge(key, take, Long::sum);
            left -= take;
        }
        o.method = Method.REVERSAL;
        o.confidence = link.confidence();
        o.decision = left == 0 ? Decision.AUTO : Decision.REVIEW;
        o.reason = left == 0 ? "Linked to original by " + link.method().toLowerCase() : "Only part of the refund could be applied";
    }

    // ------------------------------------------------------------ manual

    private void applyManual(BankTxn t, ManualDecision md, Outcome o, Result res, Ledger ledger, Map<String, Long> reversedBy) {
        long total = 0;
        for (Part p : md.parts()) {
            if (ledger.get(p.invoiceId()) == null) throw new IllegalArgumentException("Unknown invoice " + p.invoiceId());
            total += p.amount();
            if (t.direction() == Direction.CR) {
                ledger.pay(p.invoiceId(), p.amount());
                res.allocations.add(new Allocation(t.id(), p.invoiceId(), p.amount(), Method.MANUAL, 1.0));
            } else {
                ledger.reverse(p.invoiceId(), p.amount(), o.kind == TxnKind.OTHER_DEBIT ? TxnKind.REVERSAL : o.kind);
                res.allocations.add(new Allocation(t.id(), p.invoiceId(), -p.amount(), Method.MANUAL, 1.0));
            }
        }
        if (t.direction() == Direction.CR && total < t.abs()) {
            String cid = ledger.get(md.parts().get(0).invoiceId()).inv.customerId();
            res.credits.add(new Credit(t.id(), cid, t.abs() - total));
        }
        o.decision = Decision.AUTO;
        o.method = Method.MANUAL;
        o.confidence = 1.0;
        o.reason = "Confirmed by a person";
    }

    private static List<Customer> deriveCustomers(List<Invoice> invoices) {
        Map<String, Customer> m = new LinkedHashMap<>();
        for (Invoice i : invoices) m.putIfAbsent(i.customerId(), new Customer(i.customerId(), i.customerName(), "", ""));
        return new ArrayList<>(m.values());
    }
}

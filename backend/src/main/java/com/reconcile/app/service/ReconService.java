package com.reconcile.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.config.AppProperties;
import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.engine.*;
import com.reconcile.engine.Model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Loads data from the database, runs the framework-free engine, and stores what it decided. */
@Service
public class ReconService {
    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    public record RunSummary(int transactions, int auto, int review, int unmatched, int classified, long millis, String scorer,
                             List<String> warnings) {}

    private final CustomerRepo customers;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final AliasRepo aliases;
    private final AllocationRepo allocations;
    private final AdjustmentRepo adjustments;
    private final CreditRepo credits;
    private final OutcomeRepo outcomes;
    private final ManualRepo manual;
    private final CreditApplicationRepo creditApps;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private volatile boolean running = false;
    private volatile RunSummary last;

    public ReconService(CustomerRepo customers, InvoiceRepo invoices, TxnRepo txns, AliasRepo aliases, AllocationRepo allocations,
                        AdjustmentRepo adjustments, CreditRepo credits, OutcomeRepo outcomes, ManualRepo manual,
                        CreditApplicationRepo creditApps, AppProperties props, ObjectMapper mapper, PlatformTransactionManager tm) {
        this.customers = customers; this.invoices = invoices; this.txns = txns; this.aliases = aliases;
        this.allocations = allocations; this.adjustments = adjustments; this.credits = credits;
        this.outcomes = outcomes; this.manual = manual; this.creditApps = creditApps; this.props = props; this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }

    public boolean isRunning() { return running; }

    public RunSummary lastRun() { return last; }

    /** Runs on a background thread. Calls made while a run is in flight are coalesced into one follow-up run. */
    public void runAsync() {
        queued.set(true);
        executor.submit(() -> {
            if (!queued.getAndSet(false)) return;
            try { run(); } catch (Exception e) { log.error("Reconciliation run failed", e); }
        });
    }

    public RunSummary run() {
        lock.lock();
        running = true;
        try {
            long t0 = System.currentTimeMillis();
            RunSummary summary = tx.execute(status -> doRun(t0));
            last = summary;
            return summary;
        } finally {
            running = false;
            lock.unlock();
        }
    }

    /**
     * Admin reset: removes all business data (customers, invoices, bank transactions, results, decisions, learned aliases).
     * Users, the audit log and trained models are kept. Waits for any run in flight.
     */
    public void wipeBusinessData() {
        lock.lock();
        try {
            tx.executeWithoutResult(status -> {
                allocations.deleteAllInBatch(); adjustments.deleteAllInBatch(); credits.deleteAllInBatch(); outcomes.deleteAllInBatch();
                creditApps.deleteAllInBatch(); manual.deleteAllInBatch(); aliases.deleteAllInBatch();
                invoices.deleteAllInBatch(); txns.deleteAllInBatch(); customers.deleteAllInBatch();
            });
            last = null;
        } finally {
            lock.unlock();
        }
    }

    private RunSummary doRun(long t0) {
        List<Customer> cs = new ArrayList<>();
        for (CustomerE c : customers.findAll()) cs.add(new Customer(c.id, c.name, c.phone == null ? "" : c.phone, c.accountLast4 == null ? "" : c.accountLast4));
        List<InvoiceE> invoiceRows = invoices.findAll();
        List<Invoice> invs = new ArrayList<>();
        for (InvoiceE i : invoiceRows) invs.add(new Invoice(i.id, i.customerId, i.customerName, i.amount, i.issueDate, i.dueDate));
        List<BankTxn> ts = new ArrayList<>();
        for (TxnE t : txns.findAll())
            ts.add(new BankTxn(t.id, t.txnDate, t.amount, Direction.valueOf(t.direction), nz(t.payer), nz(t.channel), nz(t.bankRef), nz(t.reversalRef), nz(t.narration)));
        List<Alias> als = new ArrayList<>();
        for (AliasE a : aliases.findAll()) als.add(new Alias(a.payer, a.customerId));
        Map<String, List<Part>> byTxn = new LinkedHashMap<>();
        for (ManualE m : manual.findAll()) byTxn.computeIfAbsent(m.txnId, k -> new ArrayList<>()).add(new Part(m.invoiceId, m.amount));
        List<ManualDecision> decisions = new ArrayList<>();
        byTxn.forEach((id, parts) -> decisions.add(new ManualDecision(id, parts)));
        List<CreditApplication> applications = new ArrayList<>();
        for (CreditApplicationE c : creditApps.findAll()) applications.add(new CreditApplication(c.txnId, c.invoiceId, c.amount));

        FallbackScorer scorer = new FallbackScorer(props.mlEnabled() ? new HttpScorer(props.mlUrl()) : null);
        Reconciler.Result res = new Reconciler(new Config(), scorer).run(new Reconciler.Input(cs, invs, ts, als, decisions, applications));

        allocations.deleteAllInBatch(); adjustments.deleteAllInBatch(); credits.deleteAllInBatch(); outcomes.deleteAllInBatch();
        List<AllocationE> a = new ArrayList<>();
        for (Allocation x : res.allocations) {
            AllocationE e = new AllocationE();
            e.txnId = x.txnId(); e.invoiceId = x.invoiceId(); e.amount = x.amount(); e.method = x.method().name(); e.confidence = x.confidence();
            a.add(e);
        }
        allocations.saveAll(a);
        List<AdjustmentE> adj = new ArrayList<>();
        for (Adjustment x : res.adjustments) { AdjustmentE e = new AdjustmentE(); e.txnId = x.txnId(); e.invoiceId = x.invoiceId(); e.amount = x.amount(); e.type = x.type(); adj.add(e); }
        adjustments.saveAll(adj);
        List<CreditE> cr = new ArrayList<>();
        for (Credit x : res.credits) { CreditE e = new CreditE(); e.txnId = x.txnId(); e.customerId = x.customerId(); e.amount = x.amount(); cr.add(e); }
        credits.saveAll(cr);

        int auto = 0, review = 0, unmatched = 0, classified = 0;
        List<OutcomeE> out = new ArrayList<>();
        for (Outcome o : res.outcomes.values()) {
            OutcomeE e = new OutcomeE();
            e.txnId = o.txnId; e.kind = o.kind.name(); e.channel = o.channel.name(); e.decision = o.decision.name();
            e.method = o.method == null ? null : o.method.name(); e.confidence = o.confidence; e.reason = o.reason;
            e.linkedTxnId = o.linkedTxnId; e.candidatesJson = candidatesJson(o.top);
            out.add(e);
            switch (o.decision) {
                case AUTO -> auto++;
                case REVIEW -> review++;
                case UNMATCHED -> unmatched++;
                default -> classified++;
            }
        }
        outcomes.saveAll(out);

        for (InvoiceE i : invoiceRows) i.status = res.ledger.status(res.ledger.get(i.id)).name();
        invoices.saveAll(invoiceRows);
        return new RunSummary(res.outcomes.size(), auto, review, unmatched, classified, System.currentTimeMillis() - t0, res.scorerVersion,
                List.copyOf(res.warnings));
    }

    private String candidatesJson(List<Candidate> top) {
        List<Map<String, Object>> list = new ArrayList<>();
        int rank = 1;
        for (Candidate c : top) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("score", c.score);
            m.put("customerId", c.customerId);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Part p : c.parts) parts.add(Map.of("invoiceId", p.invoiceId(), "amount", p.amount()));
            m.put("parts", parts);
            m.put("adjustment", c.adjustment);
            m.put("excess", c.excess);
            Map<String, Double> f = new LinkedHashMap<>();
            for (int i = 0; i < Features.NAMES.size(); i++) f.put(Features.NAMES.get(i), c.features[i]);
            m.put("features", f);
            list.add(m);
        }
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

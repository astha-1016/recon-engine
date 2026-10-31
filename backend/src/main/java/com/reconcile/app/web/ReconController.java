package com.reconcile.app.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.app.service.DecisionService;
import com.reconcile.app.service.ReconService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ReconController {

    public record PartView(String invoiceId, String customerName, long amount, long invoiceAmount) {}
    public record CandidateView(int rank, double score, String customerId, List<PartView> parts, long adjustment, long excess) {}
    public record TxnView(String id, String date, long amount, String direction, String payer, String channel, String narration, String bankRef) {}
    public record ExceptionView(TxnView txn, String decision, double confidence, String reason, String kind, String linkedTxnId, List<CandidateView> candidates) {}
    public record ApproveRequest(Integer rank) {}
    public record AssignRequest(List<DecisionService.PartIn> parts) {}

    private final ReconService recon;
    private final DecisionService decisions;
    private final OutcomeRepo outcomes;
    private final TxnRepo txns;
    private final InvoiceRepo invoices;
    private final AllocationRepo allocations;
    private final AdjustmentRepo adjustments;
    private final CreditRepo credits;
    private final ObjectMapper mapper;

    public ReconController(ReconService recon, DecisionService decisions, OutcomeRepo outcomes, TxnRepo txns, InvoiceRepo invoices,
                           AllocationRepo allocations, AdjustmentRepo adjustments, CreditRepo credits, ObjectMapper mapper) {
        this.recon = recon; this.decisions = decisions; this.outcomes = outcomes; this.txns = txns; this.invoices = invoices;
        this.allocations = allocations; this.adjustments = adjustments; this.credits = credits; this.mapper = mapper;
    }

    // ---------------------------------------------------------------- run

    @PostMapping("/reconcile/run")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public ReconService.RunSummary run() { return recon.run(); }

    @GetMapping("/reconcile/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", recon.isRunning());
        m.put("lastRun", recon.lastRun());
        return m;
    }

    // ---------------------------------------------------------------- dashboard

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        long auto = outcomes.countByDecision("AUTO"), review = outcomes.countByDecision("REVIEW");
        long unmatched = outcomes.countByDecision("UNMATCHED"), classified = outcomes.countByDecision("CLASSIFIED");
        Map<String, TxnE> byId = txns.findAll().stream().collect(Collectors.toMap(t -> t.id, t -> t));
        long pendingValue = 0, unmatchedValue = 0;
        for (OutcomeE o : outcomes.findByDecisionIn(List.of("REVIEW", "UNMATCHED"))) {
            TxnE t = byId.get(o.txnId);
            if (t == null || t.amount <= 0) continue;
            if (o.decision.equals("REVIEW")) pendingValue += t.amount; else unmatchedValue += t.amount;
        }
        Map<String, Long> alloc = new HashMap<>();
        for (AllocationE a : allocations.findAll()) alloc.merge(a.invoiceId, a.amount, Long::sum);
        for (AdjustmentE a : adjustments.findAll()) alloc.merge(a.invoiceId, a.amount, Long::sum);
        long outstanding = 0;
        Map<String, Long> byStatus = new TreeMap<>();
        for (InvoiceE i : invoices.findAll()) {
            outstanding += Math.max(0, i.amount - alloc.getOrDefault(i.id, 0L));
            byStatus.merge(i.status, 1L, Long::sum);
        }
        long creditTotal = credits.findAll().stream().mapToLong(c -> c.amount).sum();
        long handled = auto + review + unmatched;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("autoMatched", auto); m.put("pendingReview", review); m.put("unmatched", unmatched); m.put("classified", classified);
        m.put("autoMatchRate", handled == 0 ? 0 : auto / (double) handled);
        m.put("pendingReviewValue", pendingValue); m.put("unmatchedValue", unmatchedValue);
        m.put("outstandingTotal", outstanding); m.put("unappliedCredits", creditTotal);
        m.put("invoicesByStatus", byStatus);
        m.put("lastRun", recon.lastRun()); m.put("running", recon.isRunning());
        return m;
    }

    // ---------------------------------------------------------------- exceptions queue

    @GetMapping("/exceptions")
    public List<ExceptionView> exceptions(@RequestParam(defaultValue = "200") int limit) throws Exception {
        List<OutcomeE> list = new ArrayList<>(outcomes.findByDecisionIn(List.of("REVIEW", "UNMATCHED")));
        Map<String, TxnE> txnById = txns.findByIdIn(list.stream().map(o -> o.txnId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(t -> t.id, t -> t));
        list.sort(Comparator.comparing((OutcomeE o) -> o.decision.equals("REVIEW") ? 0 : 1)
                .thenComparing(o -> txnById.get(o.txnId).txnDate).thenComparing(o -> o.txnId));
        if (list.size() > limit) list = list.subList(0, limit);

        Map<String, List<Map<String, Object>>> parsed = new HashMap<>();
        Set<String> invoiceIds = new HashSet<>();
        for (OutcomeE o : list) {
            List<Map<String, Object>> cs = o.candidatesJson == null || o.candidatesJson.isBlank() ? List.of()
                    : mapper.readValue(o.candidatesJson, new TypeReference<List<Map<String, Object>>>() {});
            parsed.put(o.txnId, cs);
            for (Map<String, Object> c : cs) for (Map<String, Object> p : parts(c)) invoiceIds.add((String) p.get("invoiceId"));
        }
        Map<String, InvoiceE> inv = invoices.findByIdIn(invoiceIds).stream().collect(Collectors.toMap(i -> i.id, i -> i));

        List<ExceptionView> out = new ArrayList<>();
        for (OutcomeE o : list) {
            TxnE t = txnById.get(o.txnId);
            List<CandidateView> cands = new ArrayList<>();
            for (Map<String, Object> c : parsed.get(o.txnId)) {
                List<PartView> pv = new ArrayList<>();
                for (Map<String, Object> p : parts(c)) {
                    InvoiceE i = inv.get((String) p.get("invoiceId"));
                    pv.add(new PartView((String) p.get("invoiceId"), i == null ? "" : i.customerName, ((Number) p.get("amount")).longValue(), i == null ? 0 : i.amount));
                }
                cands.add(new CandidateView(((Number) c.get("rank")).intValue(), ((Number) c.get("score")).doubleValue(), (String) c.get("customerId"), pv,
                        ((Number) c.get("adjustment")).longValue(), ((Number) c.get("excess")).longValue()));
            }
            out.add(new ExceptionView(new TxnView(t.id, t.txnDate.toString(), t.amount, t.direction, t.payer, t.channel, t.narration, t.bankRef),
                    o.decision, o.confidence, o.reason, o.kind, o.linkedTxnId, cands));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parts(Map<String, Object> candidate) { return (List<Map<String, Object>>) candidate.get("parts"); }

    // ---------------------------------------------------------------- decisions

    @PostMapping("/exceptions/{txnId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> approve(@PathVariable String txnId, @RequestBody(required = false) ApproveRequest req, Authentication auth) {
        decisions.approve(txnId, req == null || req.rank() == null ? 1 : req.rank(), auth.getName());
        return Map.of("status", "accepted");
    }

    @PostMapping("/exceptions/{txnId}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> assign(@PathVariable String txnId, @RequestBody AssignRequest req, Authentication auth) {
        decisions.assign(txnId, req.parts(), auth.getName());
        return Map.of("status", "accepted");
    }

    @PostMapping("/exceptions/{txnId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> reject(@PathVariable String txnId, Authentication auth) {
        decisions.reject(txnId, auth.getName());
        return Map.of("status", "recorded");
    }
}

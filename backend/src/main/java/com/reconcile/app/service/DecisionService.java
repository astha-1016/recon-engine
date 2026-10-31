package com.reconcile.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.engine.Money;
import com.reconcile.engine.TextNorm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Human decisions on exceptions. Every decision (1) is stored and honoured on every later run,
 * (2) teaches the payer-alias table immediately, and (3) becomes a labelled training row for the ML model.
 */
@Service
public class DecisionService {
    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    public record PartIn(String invoiceId, String amount) {}

    private final OutcomeRepo outcomes;
    private final TxnRepo txns;
    private final InvoiceRepo invoices;
    private final AllocationRepo allocations;
    private final ManualRepo manual;
    private final AliasRepo aliases;
    private final FeedbackRepo feedback;
    private final AuditRepo audit;
    private final ReconService recon;
    private final MlClient ml;
    private final ObjectMapper mapper;

    public DecisionService(OutcomeRepo outcomes, TxnRepo txns, InvoiceRepo invoices, AllocationRepo allocations, ManualRepo manual,
                           AliasRepo aliases, FeedbackRepo feedback, AuditRepo audit, ReconService recon, MlClient ml, ObjectMapper mapper) {
        this.outcomes = outcomes; this.txns = txns; this.invoices = invoices; this.allocations = allocations; this.manual = manual;
        this.aliases = aliases; this.feedback = feedback; this.audit = audit; this.recon = recon; this.ml = ml; this.mapper = mapper;
    }

    /** Accept one of the engine's suggestions (rank 1 = best). */
    @Transactional
    public void approve(String txnId, int rank, String actor) {
        OutcomeE o = outcome(txnId);
        List<Map<String, Object>> cands = candidates(o);
        List<Map<String, Object>> parts;
        Map<String, Object> chosen = null;
        if (!cands.isEmpty()) {
            chosen = cands.stream().filter(c -> ((Number) c.get("rank")).intValue() == rank).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No suggestion with rank " + rank));
            parts = castParts(chosen.get("parts"));
        } else if (o.linkedTxnId != null && !o.linkedTxnId.isBlank()) {
            parts = reversalParts(txnId, o.linkedTxnId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to approve: no suggestion for this transaction");
        }
        save(txnId, parts, actor);
        recordFeedback(txnId, cands, chosen == null ? -1 : rank, "APPROVED", actor);
        auditLog(txnId, "APPROVE", "rank=" + rank, actor);
        recon.runAsync();
    }

    /** Re-assign or manually split a payment across any invoices. */
    @Transactional
    public void assign(String txnId, List<PartIn> in, String actor) {
        TxnE t = txns.findById(txnId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown transaction"));
        if (in == null || in.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at least one invoice");
        List<Map<String, Object>> parts = new ArrayList<>();
        long total = 0;
        for (PartIn p : in) {
            long amt = Money.parse(p.amount());
            if (amt <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amounts must be positive");
            if (!invoices.existsById(p.invoiceId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown invoice " + p.invoiceId());
            total += amt;
            parts.add(Map.of("invoiceId", p.invoiceId(), "amount", amt));
        }
        if (total > Math.abs(t.amount)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocated total exceeds the transaction amount");
        save(txnId, parts, actor);
        OutcomeE o = outcomes.findById(txnId).orElse(null);
        List<Map<String, Object>> cands = o == null ? List.of() : candidates(o);
        recordFeedback(txnId, cands, matchingRank(cands, parts), "ASSIGNED", actor);
        auditLog(txnId, "ASSIGN", mapper.valueToTree(in).toString(), actor);
        recon.runAsync();
    }

    /** The suggestions were wrong. They become negative training examples; the item stays in the queue. */
    @Transactional
    public void reject(String txnId, String actor) {
        OutcomeE o = outcome(txnId);
        recordFeedback(txnId, candidates(o), 0, "REJECTED", actor);
        auditLog(txnId, "REJECT", "suggestions rejected", actor);
    }

    // ------------------------------------------------------------------ helpers

    private void save(String txnId, List<Map<String, Object>> parts, String actor) {
        manual.deleteByTxnId(txnId);
        manual.flush();
        for (Map<String, Object> p : parts) {
            ManualE m = new ManualE();
            m.txnId = txnId;
            m.invoiceId = (String) p.get("invoiceId");
            m.amount = ((Number) p.get("amount")).longValue();
            m.decidedBy = actor;
            manual.save(m);
        }
        learnAlias(txnId, parts);
    }

    /** If the person's assignment equals one of the suggestions, that suggestion was right (positive label). */
    private int matchingRank(List<Map<String, Object>> cands, List<Map<String, Object>> parts) {
        Set<String> want = new HashSet<>();
        for (Map<String, Object> p : parts) want.add(p.get("invoiceId") + ":" + ((Number) p.get("amount")).longValue());
        for (Map<String, Object> c : cands) {
            Set<String> have = new HashSet<>();
            for (Map<String, Object> p : castParts(c.get("parts"))) have.add(p.get("invoiceId") + ":" + ((Number) p.get("amount")).longValue());
            if (have.equals(want)) return ((Number) c.get("rank")).intValue();
        }
        return -2;
    }

    private void learnAlias(String txnId, List<Map<String, Object>> parts) {
        TxnE t = txns.findById(txnId).orElse(null);
        if (t == null || !"CR".equals(t.direction) || t.payer == null || t.payer.isBlank()) return;
        Set<String> owners = new HashSet<>();
        for (Map<String, Object> p : parts) invoices.findById((String) p.get("invoiceId")).ifPresent(i -> owners.add(i.customerId));
        if (owners.size() != 1) return;
        String customer = owners.iterator().next();
        String key = TextNorm.aliasKey(t.payer);
        AliasE a = aliases.findByPayerKeyAndCustomerId(key, customer).orElseGet(() -> {
            AliasE n = new AliasE();
            n.payer = t.payer; n.payerKey = key; n.customerId = customer; n.confirmedCount = 0;
            return n;
        });
        a.confirmedCount++;
        aliases.save(a);
    }

    /** rank > 0: that suggestion was right. rank 0: all rejected. rank < 0: a custom assignment. */
    private void recordFeedback(String txnId, List<Map<String, Object>> cands, int chosenRank, String decision, String actor) {
        List<Map<String, Object>> toMl = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            int label = ((Number) c.get("rank")).intValue() == chosenRank ? 1 : 0;
            FeedbackE f = new FeedbackE();
            f.txnId = txnId;
            f.label = label;
            f.systemScore = ((Number) c.get("score")).doubleValue();
            f.humanDecision = decision;
            f.decidedBy = actor;
            try { f.featuresJson = mapper.writeValueAsString(c.get("features")); } catch (Exception e) { f.featuresJson = "{}"; }
            feedback.save(f);
            toMl.add(Map.of("txn_id", txnId, "label", label, "features", c.get("features")));
        }
        try {
            ml.feedback(toMl);
        } catch (Exception e) {
            log.warn("Could not send feedback to the ML service now; it stays in feedback_log: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> reversalParts(String txnId, String linkedTxnId) {
        long left = Math.abs(txns.findById(txnId).orElseThrow().amount);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (AllocationE a : allocations.findByTxnId(linkedTxnId)) {
            if (a.amount <= 0 || left <= 0) continue;
            long take = Math.min(left, a.amount);
            parts.add(Map.of("invoiceId", a.invoiceId, "amount", take));
            left -= take;
        }
        if (parts.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The original payment has no allocations yet");
        return parts;
    }

    private OutcomeE outcome(String txnId) {
        return outcomes.findById(txnId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No outcome for " + txnId + ". Run reconciliation first."));
    }

    private List<Map<String, Object>> candidates(OutcomeE o) {
        if (o.candidatesJson == null || o.candidatesJson.isBlank()) return List.of();
        try {
            return mapper.readValue(o.candidatesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt candidate data for " + o.txnId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castParts(Object o) { return (List<Map<String, Object>>) o; }

    private void auditLog(String txnId, String action, String detail, String actor) {
        AuditE a = new AuditE();
        a.entityType = "TRANSACTION"; a.entityId = txnId; a.action = action; a.detail = detail; a.actor = actor;
        audit.save(a);
    }
}

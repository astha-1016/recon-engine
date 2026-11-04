package com.reconcile.app.web;

import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.app.service.DecisionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/** What people have decided by hand, and the way to take a decision back. */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    public record PartView(String invoiceId, String customerName, long amount) {}
    public record DecisionView(String txnId, String date, long txnAmount, String direction, String payer, String decidedBy,
                               String decidedAt, List<PartView> parts) {}

    private final ManualRepo manual;
    private final TxnRepo txns;
    private final InvoiceRepo invoices;
    private final DecisionService decisions;

    public DecisionController(ManualRepo manual, TxnRepo txns, InvoiceRepo invoices, DecisionService decisions) {
        this.manual = manual; this.txns = txns; this.invoices = invoices; this.decisions = decisions;
    }

    @GetMapping
    public List<DecisionView> list() {
        Map<String, List<ManualE>> byTxn = new LinkedHashMap<>();
        for (ManualE m : manual.findAll()) byTxn.computeIfAbsent(m.txnId, k -> new ArrayList<>()).add(m);
        Map<String, TxnE> txnById = txns.findByIdIn(byTxn.keySet()).stream().collect(Collectors.toMap(t -> t.id, t -> t));
        Set<String> invoiceIds = manual.findAll().stream().map(m -> m.invoiceId).collect(Collectors.toSet());
        Map<String, InvoiceE> inv = invoices.findByIdIn(invoiceIds).stream().collect(Collectors.toMap(i -> i.id, i -> i));
        List<DecisionView> out = new ArrayList<>();
        for (Map.Entry<String, List<ManualE>> e : byTxn.entrySet()) {
            TxnE t = txnById.get(e.getKey());
            if (t == null) continue;
            List<PartView> parts = new ArrayList<>();
            for (ManualE m : e.getValue()) {
                InvoiceE i = inv.get(m.invoiceId);
                parts.add(new PartView(m.invoiceId, i == null ? "" : i.customerName, m.amount));
            }
            ManualE first = e.getValue().get(0);
            out.add(new DecisionView(t.id, t.txnDate.toString(), t.amount, t.direction, t.payer, first.decidedBy, first.decidedAt.toString(), parts));
        }
        out.sort(Comparator.comparing(DecisionView::decidedAt).reversed());
        return out;
    }

    @PostMapping("/{txnId}/undo")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> undo(@PathVariable String txnId, Authentication auth) {
        decisions.undo(txnId, auth.getName());
        return Map.of("status", "undone");
    }
}

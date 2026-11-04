package com.reconcile.app.service;

import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.engine.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Unapplied credits: the surplus of a payment that paid more than its invoices needed. A person can put it against
 * another open invoice of the same customer. Applications are stored, honoured on every run, and can be removed.
 */
@Service
public class CreditService {

    public record InvoiceOption(String id, long outstanding, String dueDate) {}
    public record CreditView(String txnId, String txnDate, String payer, String customerId, String customerName, long amount,
                             List<InvoiceOption> openInvoices) {}
    public record ApplicationView(long id, String txnId, String invoiceId, long amount, String appliedBy, String appliedAt) {}

    private final CreditRepo credits;
    private final CreditApplicationRepo applications;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final AllocationRepo allocations;
    private final AdjustmentRepo adjustments;
    private final AuditRepo audit;
    private final ReconService recon;

    public CreditService(CreditRepo credits, CreditApplicationRepo applications, InvoiceRepo invoices, TxnRepo txns,
                         AllocationRepo allocations, AdjustmentRepo adjustments, AuditRepo audit, ReconService recon) {
        this.credits = credits; this.applications = applications; this.invoices = invoices; this.txns = txns;
        this.allocations = allocations; this.adjustments = adjustments; this.audit = audit; this.recon = recon;
    }

    /** Money already settled against each invoice (cash allocations plus write-off adjustments). */
    private Map<String, Long> settled() {
        Map<String, Long> m = new HashMap<>();
        for (AllocationE a : allocations.findAll()) m.merge(a.invoiceId, a.amount, Long::sum);
        for (AdjustmentE a : adjustments.findAll()) m.merge(a.invoiceId, a.amount, Long::sum);
        return m;
    }

    @Transactional(readOnly = true)
    public List<CreditView> list() {
        Map<String, Long> settled = settled();
        Map<String, TxnE> txnById = txns.findByIdIn(credits.findAll().stream().map(c -> c.txnId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(t -> t.id, t -> t));
        List<CreditView> out = new ArrayList<>();
        for (CreditE c : credits.findAll()) {
            TxnE t = txnById.get(c.txnId);
            List<InvoiceE> mine = c.customerId == null ? List.of() : invoices.findByCustomerId(c.customerId);
            List<InvoiceOption> open = new ArrayList<>();
            for (InvoiceE i : mine) {
                long owed = i.amount - settled.getOrDefault(i.id, 0L);
                if (owed > 0) open.add(new InvoiceOption(i.id, owed, i.dueDate.toString()));
            }
            open.sort(Comparator.comparing(InvoiceOption::dueDate).thenComparing(InvoiceOption::id));
            String name = mine.isEmpty() ? "" : mine.get(0).customerName;
            out.add(new CreditView(c.txnId, t == null ? "" : t.txnDate.toString(), t == null ? "" : t.payer, c.customerId, name, c.amount, open));
        }
        out.sort(Comparator.comparing(CreditView::txnDate).thenComparing(CreditView::txnId));
        return out;
    }

    @Transactional
    public void apply(String txnId, String invoiceId, String amountText, String actor) {
        CreditE credit = credits.findFirstByTxnId(txnId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No unapplied credit for " + txnId + ". Run reconciliation first."));
        long amount;
        try {
            amount = Money.parse(amountText);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount is not a valid number");
        }
        if (amount <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        if (amount > credit.amount) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only " + Money.format(credit.amount) + " of this credit is left");
        InvoiceE invoice = invoices.findById(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice " + invoiceId));
        if (!invoice.customerId.equals(credit.customerId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A credit can only be applied to an invoice of the same customer");
        long owed = invoice.amount - settled().getOrDefault(invoiceId, 0L);
        if (amount > owed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That invoice only has " + Money.format(Math.max(0, owed)) + " outstanding");

        CreditApplicationE a = new CreditApplicationE();
        a.txnId = txnId; a.invoiceId = invoiceId; a.amount = amount; a.appliedBy = actor;
        applications.save(a);
        log("CREDIT", txnId, "APPLY_CREDIT", Money.format(amount) + " to " + invoiceId, actor);
        recon.runAsync();
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> applications() {
        return applications.findAll().stream()
                .map(a -> new ApplicationView(a.id, a.txnId, a.invoiceId, a.amount, a.appliedBy, a.appliedAt.toString()))
                .sorted(Comparator.comparing(ApplicationView::appliedAt).reversed())
                .collect(Collectors.toList());
    }

    @Transactional
    public void remove(long applicationId, String actor) {
        CreditApplicationE a = applications.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such credit application"));
        applications.delete(a);
        log("CREDIT", a.txnId, "REMOVE_CREDIT_APPLICATION", Money.format(a.amount) + " from " + a.invoiceId, actor);
        recon.runAsync();
    }

    private void log(String type, String id, String action, String detail, String actor) {
        AuditE e = new AuditE();
        e.entityType = type; e.entityId = id; e.action = action; e.detail = detail; e.actor = actor;
        audit.save(e);
    }
}

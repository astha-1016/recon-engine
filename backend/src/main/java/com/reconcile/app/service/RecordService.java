package com.reconcile.app.service;

import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day-to-day maintenance of invoices and bank transactions from the app (no file needed): create, edit, delete.
 * Every row goes through the same validation as an uploaded file, every change is written to the audit log,
 * and the engine re-runs in the background afterwards because allocations are always derived from the data.
 * Rows that a person has already decided on (manual decision or credit application) are locked: undo first.
 */
@Service
public class RecordService {

    public record InvoiceIn(String invoiceId, String customerId, String customerName, String amount, String invoiceDate, String dueDate) {}

    public record TxnIn(String transactionId, String transactionDate, String amount, String direction, String payerName,
                        String channel, String bankReference, String reversalReference, String narration) {}

    private final IngestService ingest;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final AllocationRepo allocations;
    private final CreditRepo credits;
    private final OutcomeRepo outcomes;
    private final ManualRepo manual;
    private final CreditApplicationRepo creditApps;
    private final AuditRepo audit;
    private final ReconService recon;

    public RecordService(IngestService ingest, InvoiceRepo invoices, TxnRepo txns, AllocationRepo allocations, CreditRepo credits,
                         OutcomeRepo outcomes, ManualRepo manual, CreditApplicationRepo creditApps, AuditRepo audit, ReconService recon) {
        this.ingest = ingest; this.invoices = invoices; this.txns = txns; this.allocations = allocations; this.credits = credits;
        this.outcomes = outcomes; this.manual = manual; this.creditApps = creditApps; this.audit = audit; this.recon = recon;
    }

    // ------------------------------------------------------------------ invoices

    @Transactional
    public void createInvoice(InvoiceIn in, String actor) {
        Map<String, String> row = invoiceRow(in.invoiceId(), in);
        requireInserted(ingest.ingest("invoices", List.of(row)), "Invoice " + nz(in.invoiceId()).trim());
        audit("INVOICE", nz(in.invoiceId()).trim(), "CREATE", row.toString(), actor);
        rerunAfterCommit();
    }

    @Transactional
    public void updateInvoice(String id, InvoiceIn in, String actor) {
        InvoiceE e = invoices.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
        lockedIfDecided(manual.existsByInvoiceId(id) || creditApps.existsByInvoiceId(id), "Invoice " + id);
        InvoiceE n = validated(() -> IngestService.toInvoice(invoiceRow(id, in)));
        e.customerId = n.customerId; e.customerName = n.customerName; e.amount = n.amount;
        e.issueDate = n.issueDate; e.dueDate = n.dueDate; e.rowHash = n.rowHash;
        invoices.save(e);
        audit("INVOICE", id, "UPDATE", invoiceRow(id, in).toString(), actor);
        rerunAfterCommit();
    }

    @Transactional
    public void deleteInvoice(String id, String actor) {
        if (!invoices.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice");
        lockedIfDecided(manual.existsByInvoiceId(id) || creditApps.existsByInvoiceId(id), "Invoice " + id);
        allocations.deleteAll(allocations.findByInvoiceId(id));
        invoices.deleteById(id);
        audit("INVOICE", id, "DELETE", "invoice removed", actor);
        rerunAfterCommit();
    }

    // ------------------------------------------------------------------ bank transactions

    @Transactional
    public void createTxn(TxnIn in, String actor) {
        Map<String, String> row = txnRow(in.transactionId(), in);
        requireInserted(ingest.ingest("transactions", List.of(row)), "Transaction " + nz(in.transactionId()).trim());
        audit("TXN", nz(in.transactionId()).trim(), "CREATE", row.toString(), actor);
        rerunAfterCommit();
    }

    @Transactional
    public void updateTxn(String id, TxnIn in, String actor) {
        TxnE e = txns.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown transaction"));
        lockedIfDecided(manual.existsByTxnId(id) || creditApps.existsByTxnId(id), "Transaction " + id);
        TxnE n = validated(() -> IngestService.toTxn(txnRow(id, in)));
        e.txnDate = n.txnDate; e.amount = n.amount; e.direction = n.direction; e.payer = n.payer; e.channel = n.channel;
        e.bankRef = n.bankRef; e.reversalRef = n.reversalRef; e.narration = n.narration; e.rowHash = n.rowHash;
        txns.save(e);
        audit("TXN", id, "UPDATE", txnRow(id, in).toString(), actor);
        rerunAfterCommit();
    }

    @Transactional
    public void deleteTxn(String id, String actor) {
        if (!txns.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown transaction");
        lockedIfDecided(manual.existsByTxnId(id) || creditApps.existsByTxnId(id), "Transaction " + id);
        // derived rows go now so no screen shows a result for a payment that no longer exists; the re-run rebuilds the rest
        allocations.deleteAll(allocations.findByTxnId(id));
        credits.findFirstByTxnId(id).ifPresent(credits::delete);
        if (outcomes.existsById(id)) outcomes.deleteById(id);
        txns.deleteById(id);
        audit("TXN", id, "DELETE", "transaction removed", actor);
        rerunAfterCommit();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> invoiceRow(String id, InvoiceIn in) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("invoice_id", nz(id)); r.put("customer_id", nz(in.customerId())); r.put("customer_name", nz(in.customerName()));
        r.put("amount", nz(in.amount())); r.put("invoice_date", nz(in.invoiceDate())); r.put("due_date", nz(in.dueDate()));
        return r;
    }

    private static Map<String, String> txnRow(String id, TxnIn in) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("transaction_id", nz(id)); r.put("transaction_date", nz(in.transactionDate())); r.put("amount", nz(in.amount()));
        r.put("direction", nz(in.direction())); r.put("payer_name", nz(in.payerName())); r.put("channel", nz(in.channel()));
        r.put("bank_reference", nz(in.bankReference())); r.put("reversal_reference", nz(in.reversalReference())); r.put("narration", nz(in.narration()));
        return r;
    }

    private static <T> T validated(java.util.function.Supplier<T> parse) {
        try {
            return parse.get();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static void requireInserted(IngestService.Report rep, String what) {
        if (!rep.errors().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rep.errors().get(0).replaceFirst("^row \\d+: ", ""));
        if (rep.inserted() == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, what + " already exists");
    }

    private static void lockedIfDecided(boolean decided, String what) {
        if (decided)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    what + " is part of a manual decision or credit application. Undo it first (Decisions / Credits tab), then change it.");
    }

    /** The background run must see the committed change, so it is started only once the transaction has committed. */
    private void rerunAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { recon.runAsync(); }
            });
        } else {
            recon.runAsync();
        }
    }

    private void audit(String type, String id, String action, String detail, String actor) {
        AuditE a = new AuditE();
        a.entityType = type; a.entityId = id; a.action = action; a.detail = detail; a.actor = actor;
        audit.save(a);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

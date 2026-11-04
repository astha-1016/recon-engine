package com.reconcile.app.service;

import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.engine.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Validates uploaded rows and stores them. Re-uploading the same file is harmless (rows are hashed). */
@Service
public class IngestService {

    public record Report(int rows, int inserted, int duplicates, List<String> errors) {}

    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final CustomerRepo customers;

    public IngestService(InvoiceRepo invoices, TxnRepo txns, CustomerRepo customers) {
        this.invoices = invoices;
        this.txns = txns;
        this.customers = customers;
    }

    @Transactional
    public Report ingest(String kind, List<Map<String, String>> rows) {
        return switch (kind) {
            case "invoices" -> invoices(rows);
            case "transactions" -> transactions(rows);
            case "customers" -> customers(rows);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown upload kind: " + kind);
        };
    }

    private Report invoices(List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        int inserted = 0, duplicates = 0, line = 1;
        for (Map<String, String> r : rows) {
            line++;
            try {
                InvoiceE e = toInvoice(r);
                if (invoices.existsByRowHash(e.rowHash)) { duplicates++; continue; }
                if (invoices.existsById(e.id)) throw new IllegalArgumentException("invoice " + e.id + " already exists with different content");
                invoices.save(e);
                inserted++;
            } catch (RuntimeException ex) {
                errors.add("row " + line + ": " + ex.getMessage());
            }
        }
        return new Report(rows.size(), inserted, duplicates, errors);
    }

    private Report transactions(List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        int inserted = 0, duplicates = 0, line = 1;
        for (Map<String, String> r : rows) {
            line++;
            try {
                TxnE e = toTxn(r);
                if (txns.existsByRowHash(e.rowHash)) { duplicates++; continue; }
                if (txns.existsById(e.id)) throw new IllegalArgumentException("transaction " + e.id + " already exists with different content");
                txns.save(e);
                inserted++;
            } catch (RuntimeException ex) {
                errors.add("row " + line + ": " + ex.getMessage());
            }
        }
        return new Report(rows.size(), inserted, duplicates, errors);
    }

    private Report customers(List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        int inserted = 0, line = 1;
        for (Map<String, String> r : rows) {
            line++;
            try {
                require(r, "customer_id", "name");
                CustomerE e = customers.findById(r.get("customer_id").trim()).orElseGet(CustomerE::new);
                e.id = r.get("customer_id").trim();
                e.name = r.get("name").trim();
                e.phone = r.getOrDefault("phone", "").trim();
                e.accountLast4 = r.getOrDefault("account_last4", "").trim();
                customers.save(e);
                inserted++;
            } catch (RuntimeException ex) {
                errors.add("row " + line + ": " + ex.getMessage());
            }
        }
        return new Report(rows.size(), inserted, 0, errors);
    }

    /** Validates one invoice row and maps it to an entity. Shared by file upload and the manual create/edit forms. */
    static InvoiceE toInvoice(Map<String, String> r) {
        require(r, "invoice_id", "customer_id", "customer_name", "amount", "invoice_date", "due_date");
        InvoiceE e = new InvoiceE();
        e.id = r.get("invoice_id").trim();
        e.customerId = r.get("customer_id").trim();
        e.customerName = r.get("customer_name").trim();
        e.amount = Money.parse(r.get("amount"));
        if (e.amount <= 0) throw new IllegalArgumentException("amount must be positive");
        e.issueDate = LocalDate.parse(r.get("invoice_date").trim());
        e.dueDate = LocalDate.parse(r.get("due_date").trim());
        e.rowHash = hash(e.id, e.customerId, e.customerName, r.get("amount"), e.issueDate, e.dueDate);
        return e;
    }

    /** Validates one bank-statement row and maps it to an entity. Shared by file upload and the manual forms. */
    static TxnE toTxn(Map<String, String> r) {
        require(r, "transaction_id", "transaction_date", "amount");
        TxnE e = new TxnE();
        e.id = r.get("transaction_id").trim();
        e.txnDate = LocalDate.parse(r.get("transaction_date").trim());
        long amount = Money.parse(r.get("amount"));
        String dir = r.getOrDefault("direction", "").trim().toUpperCase();
        if (dir.isEmpty()) dir = amount < 0 ? "DR" : "CR";
        if (!dir.equals("CR") && !dir.equals("DR")) throw new IllegalArgumentException("direction must be CR or DR");
        e.direction = dir;
        e.amount = dir.equals("DR") ? -Math.abs(amount) : Math.abs(amount);
        e.payer = r.getOrDefault("payer_name", "").trim();
        e.channel = r.getOrDefault("channel", "").trim();
        e.bankRef = r.getOrDefault("bank_reference", "").trim();
        e.reversalRef = r.getOrDefault("reversal_reference", "").trim();
        e.narration = r.getOrDefault("narration", "").trim();
        e.rowHash = hash(e.id, e.txnDate, r.get("amount"), dir, e.payer, e.channel, e.bankRef, e.reversalRef, e.narration);
        return e;
    }

    private static void require(Map<String, String> row, String... columns) {
        for (String c : columns) {
            if (!row.containsKey(c)) throw new IllegalArgumentException("missing column '" + c + "'");
            if (row.get(c).isBlank()) throw new IllegalArgumentException("'" + c + "' is empty");
        }
    }

    static String hash(Object... parts) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) sb.append(p).append('|');
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

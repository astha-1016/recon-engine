package com.reconcile.engine;

import com.reconcile.engine.Model.Invoice;
import com.reconcile.engine.Model.InvoiceStatus;
import com.reconcile.engine.Model.TxnKind;

import java.util.*;

/**
 * Invoice balances and the invoice state machine.
 * Outstanding is always derived (amount - cash - adjustments), never edited directly.
 */
public final class Ledger {

    public static final class Entry {
        public final Invoice inv;
        public long cash;
        public long adjust;
        public long reversedCash;
        public int payments;
        public TxnKind reversalKind;

        Entry(Invoice inv) { this.inv = inv; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, List<Entry>> byCustomer = new LinkedHashMap<>();

    public Ledger(Collection<Invoice> invoices) {
        for (Invoice i : invoices) {
            Entry e = new Entry(i);
            entries.put(i.id(), e);
            byCustomer.computeIfAbsent(i.customerId(), k -> new ArrayList<>()).add(e);
        }
    }

    public Collection<Entry> all() { return entries.values(); }
    public Entry get(String invoiceId) { return entries.get(invoiceId); }
    public List<Entry> ofCustomer(String customerId) { return byCustomer.getOrDefault(customerId, List.of()); }
    public Set<String> customerIds() { return byCustomer.keySet(); }

    public long outstanding(Entry e) { return Math.max(0, e.inv.amount() - e.cash - e.adjust); }

    public void pay(String invoiceId, long amount) { Entry e = entries.get(invoiceId); e.cash += amount; e.payments++; }

    public void adjust(String invoiceId, long amount) { entries.get(invoiceId).adjust += amount; }

    public void reverse(String invoiceId, long amount, TxnKind kind) {
        Entry e = entries.get(invoiceId);
        e.cash -= amount;
        e.reversedCash += amount;
        e.reversalKind = kind;
    }

    /** OPEN -> PARTIALLY_PAID -> PAID, and PAID -> REFUNDED / DISPUTED / OPEN (cheque bounce). */
    public InvoiceStatus status(Entry e) {
        if (e.reversedCash > 0) {
            if (e.reversalKind == TxnKind.CHEQUE_RETURN && e.cash <= 0) return InvoiceStatus.OPEN;
            if (e.reversalKind == TxnKind.REFUND && e.cash <= 0) return InvoiceStatus.REFUNDED;
            return InvoiceStatus.DISPUTED;
        }
        if (e.cash + e.adjust >= e.inv.amount()) return InvoiceStatus.PAID;
        return e.cash > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.OPEN;
    }
}

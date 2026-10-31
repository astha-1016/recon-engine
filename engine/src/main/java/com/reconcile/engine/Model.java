package com.reconcile.engine;

import java.time.LocalDate;
import java.util.List;

/** Plain domain types shared by the engine, the CLI and the Spring layer. All money is in paise (long). */
public final class Model {
    private Model() {}

    public enum Direction { CR, DR }
    public enum TxnKind { PAYMENT, REFUND, REVERSAL, CHEQUE_RETURN, BANK_FEE, OTHER_DEBIT }
    public enum Channel { QR, UPI_ID, NEFT, IMPS, RTGS, CHEQUE, CASH, BANK, UNKNOWN }
    public enum PayerKind { NAME, VPA, PHONE_VPA, MASK }
    public enum Method { EXACT, FUZZY, COMPOSITE, MANUAL, REVERSAL }
    public enum Decision { AUTO, REVIEW, UNMATCHED, CLASSIFIED }
    public enum InvoiceStatus { OPEN, PARTIALLY_PAID, PAID, REFUNDED, DISPUTED }

    public record Customer(String id, String name, String phone, String accountLast4) {}

    public record Invoice(String id, String customerId, String customerName, long amount,
                          LocalDate issueDate, LocalDate dueDate) {}

    public record BankTxn(String id, LocalDate date, long amount, Direction direction, String payer,
                          String channel, String bankRef, String reversalRef, String narration) {
        public long abs() { return Math.abs(amount); }
    }

    /** One invoice share of a payment. */
    public record Part(String invoiceId, long amount) {}

    public record Allocation(String txnId, String invoiceId, long amount, Method method, double confidence) {}

    /** Non-cash settlement of an invoice balance, e.g. a short-pay written off as bank fee / TDS. */
    public record Adjustment(String txnId, String invoiceId, long amount, String type) {}

    /** Cash received above what the invoices needed; stays visible until a human allocates it. */
    public record Credit(String txnId, String customerId, long amount) {}

    public record ManualDecision(String txnId, List<Part> parts) {}

    public record Alias(String payer, String customerId) {}

    /** One possible way to explain a bank payment: which invoices get how much. */
    public static final class Candidate {
        public final List<Part> parts;
        public final long adjustment;
        public final long excess;
        public final String customerId;
        public final double[] features;
        public final boolean refHit;
        public double score;

        public Candidate(List<Part> parts, long adjustment, long excess, String customerId,
                         double[] features, boolean refHit) {
            this.parts = parts;
            this.adjustment = adjustment;
            this.excess = excess;
            this.customerId = customerId;
            this.features = features;
            this.refHit = refHit;
        }

        public String key() {
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) sb.append(p.invoiceId()).append(':').append(p.amount()).append(';');
            return sb.toString();
        }
    }

    /** What the engine decided for one bank transaction, with suggestions for the review queue. */
    public static final class Outcome {
        public final String txnId;
        public TxnKind kind = TxnKind.PAYMENT;
        public Channel channel = Channel.UNKNOWN;
        public Decision decision = Decision.UNMATCHED;
        public Method method;
        public double confidence;
        public String reason = "";
        public String linkedTxnId = "";
        public List<Candidate> top = List.of();

        public Outcome(String txnId) { this.txnId = txnId; }
    }
}

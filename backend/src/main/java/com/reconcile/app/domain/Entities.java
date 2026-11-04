package com.reconcile.app.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/** JPA entities. Money is stored as paise (long) so no floating point ever touches an amount. */
public final class Entities {
    private Entities() {}

    @Entity @Table(name = "customers")
    public static class CustomerE {
        @Id public String id;
        public String name;
        public String phone;
        public String accountLast4;
    }

    @Entity @Table(name = "invoices", uniqueConstraints = @UniqueConstraint(columnNames = "rowHash"))
    public static class InvoiceE {
        @Id public String id;
        public String customerId;
        public String customerName;
        public long amount;
        public LocalDate issueDate;
        public LocalDate dueDate;
        /** OPEN, PARTIALLY_PAID, PAID, REFUNDED, DISPUTED. Derived by the engine's ledger on every run. */
        public String status = "OPEN";
        public String rowHash;
    }

    @Entity @Table(name = "bank_transactions", uniqueConstraints = @UniqueConstraint(columnNames = "rowHash"))
    public static class TxnE {
        @Id public String id;
        public LocalDate txnDate;
        /** Signed: credits positive, debits negative. */
        public long amount;
        public String direction;
        public String payer;
        public String channel;
        public String bankRef;
        public String reversalRef;
        @Column(length = 1000) public String narration;
        public String rowHash;
    }

    @Entity @Table(name = "payer_aliases", uniqueConstraints = @UniqueConstraint(columnNames = {"payerKey", "customerId"}))
    public static class AliasE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String payer;
        public String payerKey;
        public String customerId;
        /** HUMAN_CONFIRMED aliases come from reviewers; they are the main driver of identity resolution. */
        public String source = "HUMAN_CONFIRMED";
        public int confirmedCount = 1;
        public Instant createdAt = Instant.now();
    }

    @Entity(name = "AllocationE") @Table(name = "allocations")
    public static class AllocationE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public String invoiceId;
        public long amount;
        public String method;
        public double confidence;
    }

    @Entity @Table(name = "adjustments")
    public static class AdjustmentE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public String invoiceId;
        public long amount;
        public String type;
    }

    @Entity(name = "CreditE") @Table(name = "unapplied_credits")
    public static class CreditE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public String customerId;
        public long amount;
    }

    @Entity(name = "OutcomeE") @Table(name = "txn_outcomes")
    public static class OutcomeE {
        @Id public String txnId;
        public String kind;
        public String channel;
        /** AUTO, REVIEW, UNMATCHED or CLASSIFIED */
        public String decision;
        public String method;
        public double confidence;
        @Column(length = 1000) public String reason;
        public String linkedTxnId;
        @Column(columnDefinition = "text") public String candidatesJson;
    }

    @Entity @Table(name = "manual_decisions")
    public static class ManualE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public String invoiceId;
        public long amount;
        public String decidedBy;
        public Instant decidedAt = Instant.now();
    }

    /** A person put part of an unapplied credit (surplus of a payment) against another invoice. */
    @Entity @Table(name = "credit_applications")
    public static class CreditApplicationE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public String invoiceId;
        public long amount;
        public String appliedBy;
        public Instant appliedAt = Instant.now();
    }

    @Entity @Table(name = "feedback_log")
    public static class FeedbackE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String txnId;
        public int label;
        @Column(columnDefinition = "text") public String featuresJson;
        public double systemScore;
        /** APPROVED, REJECTED or ASSIGNED */
        public String humanDecision;
        public String decidedBy;
        public Instant decidedAt = Instant.now();
        /** Set when the decision was undone; revoked rows are kept for the audit trail but never used for training. */
        public boolean revoked;
    }

    @Entity @Table(name = "app_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
    public static class UserE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String username;
        public String passwordHash;
        public String role;
        /** Bumped whenever the password or role changes. A token carries the value it was issued with; a mismatch ends the session. */
        public int tokenVersion;
    }

    @Entity @Table(name = "audit_log")
    public static class AuditE {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String entityType;
        public String entityId;
        public String action;
        @Column(columnDefinition = "text") public String detail;
        public String actor;
        public Instant createdAt = Instant.now();
    }
}

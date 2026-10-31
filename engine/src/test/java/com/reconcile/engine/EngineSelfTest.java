package com.reconcile.engine;

import com.reconcile.engine.Model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free test runner (works with plain javac, and is wired into `mvn test` via exec-maven-plugin).
 * Uses a deterministic stand-in scorer so each test checks pipeline rules, not the ML model.
 */
public final class EngineSelfTest {
    private static int failures = 0, checks = 0;

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL  " + name); }
    }

    /** 0.95 when the amount explains the payment uniquely AND the payer is identified; otherwise 0.3. */
    static final Scorer STUB = new Scorer() {
        public double[] score(List<double[]> rows) {
            double[] out = new double[rows.size()];
            for (int i = 0; i < out.length; i++) {
                double[] f = rows.get(i);
                boolean amount = f[Features.AMOUNT_EXACT] == 1 || f[Features.FRAC_MATCH] == 1 || f[Features.SHORT_PAY] == 1 || f[Features.OVER_PAY] == 1;
                out[i] = amount && f[Features.IDENTITY] >= 0.85 ? 0.95 : 0.3;
            }
            return out;
        }
        public String version() { return "stub"; }
    };

    static LocalDate d(int day) { return LocalDate.of(2026, 7, day); }

    static Invoice inv(String id, String cust, String name, String amount, int issued) {
        return new Invoice(id, cust, name, Money.parse(amount), d(issued), d(issued).plusDays(30));
    }

    static BankTxn cr(String id, int day, String amount, String payer, String ch, String narration) {
        return new BankTxn(id, d(day), Money.parse(amount), Direction.CR, payer, ch, "UTR" + id, "", narration);
    }

    static BankTxn dr(String id, int day, String amount, String payer, String ch, String narration, String revRef) {
        return new BankTxn(id, d(day), -Money.parse(amount), Direction.DR, payer, ch, "UTR" + id, revRef, narration);
    }

    static Reconciler.Result run(List<Customer> cs, List<Invoice> invs, List<BankTxn> txns, List<Alias> aliases, List<ManualDecision> manual) {
        return new Reconciler(new Config(), STUB).run(new Reconciler.Input(cs, invs, txns, aliases, manual));
    }

    static long allocated(Reconciler.Result r, String txn, String invoice) {
        return r.allocations.stream().filter(a -> a.txnId().equals(txn) && a.invoiceId().equals(invoice)).mapToLong(Allocation::amount).sum();
    }

    static final List<Customer> CUSTOMERS = List.of(
            new Customer("C1", "Acme Traders Pvt Ltd", "9876543210", "4321"),
            new Customer("C2", "Bharat Textiles Pvt Ltd", "9123456780", ""));

    public static void main(String[] args) throws Exception {
        text();
        classifier();
        pipeline();
        featureParity();
        System.out.printf("%d checks, %d failures%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    static void text() {
        check("abbreviation + suffix normalisation", TextNorm.normalizeName("ABC TRD P LTD").equals(TextNorm.normalizeName("ABC Traders Pvt Ltd")));
        check("payer kind phone vpa", TextNorm.payerKind("9876543210@okhdfc") == PayerKind.PHONE_VPA);
        check("payer kind opaque vpa", TextNorm.payerKind("synthetic0001@exampleupi") == PayerKind.VPA);
        check("payer kind mask", TextNorm.payerKind("XXXX4321") == PayerKind.MASK);
        check("payer kind name", TextNorm.payerKind("Acme Traders") == PayerKind.NAME);
        check("mask last4", TextNorm.maskLast4("XXXX4321").equals("4321"));
        check("invoice refs", TextNorm.invoiceRefs("NEFT/inv 2026 00012/ACME").contains("INV202600012"));
        check("different businesses stay apart", Similarity.nameSim("abc traders", "abc textiles") < 0.85);
        check("same business equals", Similarity.nameSim("abc traders", "abc traders") == 1.0);
        check("typo still close", Similarity.nameSim("acme traders", "acme tradres") > 0.8);
        check("money parse", Money.parse("1,234.50") == 123450 && Money.format(-5) .equals("-0.05"));
    }

    static void classifier() {
        check("bank fee", Classifier.kind(dr("F", 1, "10", "BANK SERVICE FEE", "BANK", "MONTHLY SERVICE CHARGE", "")) == TxnKind.BANK_FEE);
        check("reversal", Classifier.kind(dr("R", 1, "10", "X", "NEFT", "NEFT REVERSAL", "")) == TxnKind.REVERSAL);
        check("refund", Classifier.kind(dr("R", 1, "10", "X", "IMPS", "IMPS REFUND", "")) == TxnKind.REFUND);
        check("cheque return", Classifier.kind(dr("R", 1, "10", "X", "CHEQUE", "CHEQUE CHQ RET RETURN", "")) == TxnKind.CHEQUE_RETURN);
        check("channel from narration", Classifier.channel(cr("A", 1, "1", "x", "", "UPI/123/ACME")) == Channel.UPI_ID);
        check("cheque gets wider window", Classifier.windowDays(Channel.CHEQUE, new Config()) > Classifier.windowDays(Channel.NEFT, new Config()));
    }

    static void pipeline() {
        // exact: reference + amount
        var r = run(CUSTOMERS, List.of(inv("INV-2026-00001", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "garbled", "NEFT", "NEFT PAYMENT INV-2026-00001")), List.of(), List.of());
        check("tier-1 exact", r.outcomes.get("T1").method == Method.EXACT && r.outcomes.get("T1").confidence == 1.0);
        check("exact -> PAID", r.ledger.status(r.ledger.get("INV-2026-00001")) == InvoiceStatus.PAID);

        // short-pay (1%) with payer identified by the phone number inside the UPI handle
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1)),
                List.of(cr("T1", 8, "9900.00", "9876543210@okhdfc", "UPI_ID", "UPI PAYMENT")), List.of(), List.of());
        check("short-pay allocates cash", allocated(r, "T1", "I1") == Money.parse("9900.00"));
        check("short-pay writes off the shortfall", r.adjustments.size() == 1 && r.adjustments.get(0).amount() == Money.parse("100.00"));
        check("short-pay closes invoice", r.ledger.status(r.ledger.get("I1")) == InvoiceStatus.PAID);

        // 5% short is a partial payment: invoice stays open
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1)),
                List.of(cr("T1", 8, "5000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT")), List.of(), List.of());
        check("half payment is a partial", r.adjustments.isEmpty() && r.ledger.status(r.ledger.get("I1")) == InvoiceStatus.PARTIALLY_PAID);

        // overpayment creates an unapplied credit
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1)),
                List.of(cr("T1", 8, "10100.00", "9876543210@oksbi", "IMPS", "IMPS PAYMENT")), List.of(), List.of());
        check("overpay allocates only the invoice", allocated(r, "T1", "I1") == Money.parse("10000.00"));
        check("overpay becomes a credit", r.credits.size() == 1 && r.credits.get(0).amount() == Money.parse("100.00"));

        // instalments: 40% then the rest
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1)),
                List.of(cr("T1", 8, "4000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT"),
                        cr("T2", 12, "6000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT")), List.of(), List.of());
        check("instalment 1 allocated", allocated(r, "T1", "I1") == Money.parse("4000.00"));
        check("instalment 2 matched against the remaining balance", allocated(r, "T2", "I1") == Money.parse("6000.00"));
        check("instalments end PAID", r.ledger.status(r.ledger.get("I1")) == InvoiceStatus.PAID);

        // composite: one payment for two of three invoices
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1), inv("I2", "C1", "Acme Traders Pvt Ltd", "2000.00", 2),
                        inv("I3", "C1", "Acme Traders Pvt Ltd", "3000.00", 3)),
                List.of(cr("T1", 10, "5000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT")), List.of(), List.of());
        check("composite picks the 2000+3000 subset", allocated(r, "T1", "I2") == Money.parse("2000.00") && allocated(r, "T1", "I3") == Money.parse("3000.00")
                && allocated(r, "T1", "I1") == 0 && r.outcomes.get("T1").method == Method.COMPOSITE);

        // refund linked by reference -> REFUNDED
        r = run(CUSTOMERS, List.of(inv("INV-2026-00001", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "x", "NEFT", "NEFT PAYMENT INV-2026-00001"),
                        dr("T2", 14, "1000.00", "Acme", "NEFT", "NEFT REFUND", "UTRT1")), List.of(), List.of());
        check("refund by reference reverses allocation", allocated(r, "T2", "INV-2026-00001") == -Money.parse("1000.00"));
        check("refund -> REFUNDED", r.ledger.status(r.ledger.get("INV-2026-00001")) == InvoiceStatus.REFUNDED);

        // reversal inferred without a reference -> DISPUTED
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "9876543210@okhdfc", "UPI_ID", "UPI PAYMENT I1"),
                        dr("T2", 14, "1000.00", "Acme Traders Pvt Ltd", "UPI_ID", "UPI REVERSAL", "")), List.of(), List.of());
        check("reversal inferred by amount", r.outcomes.get("T2").linkedTxnId.equals("T1") && allocated(r, "T2", "I1") == -Money.parse("1000.00"));
        check("bank reversal -> DISPUTED", r.ledger.status(r.ledger.get("I1")) == InvoiceStatus.DISPUTED);

        // bounced cheque reopens the invoice
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "Acme Traders Pvt Ltd", "CHEQUE", "CHEQUE PAYMENT I1"),
                        dr("T2", 12, "1000.00", "Acme Traders Pvt Ltd", "CHEQUE", "CHEQUE CHQ RET RETURN", "UTRT1")), List.of(), List.of());
        check("cheque return reopens invoice", r.ledger.status(r.ledger.get("I1")) == InvoiceStatus.OPEN);

        // bank fee is classified, never matched
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(dr("F1", 20, "10.00", "BANK SERVICE FEE", "BANK", "MONTHLY SERVICE CHARGE", "")), List.of(), List.of());
        check("bank fee classified, no allocation", r.outcomes.get("F1").decision == Decision.CLASSIFIED && r.allocations.isEmpty());

        // FIFO tie-break among interchangeable invoices of one customer
        r = run(CUSTOMERS, List.of(inv("I2", "C1", "Acme Traders Pvt Ltd", "1000.00", 5), inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 12, "1000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT")), List.of(), List.of());
        check("FIFO picks the oldest due date", allocated(r, "T1", "I1") == Money.parse("1000.00") && allocated(r, "T1", "I2") == 0);

        // genuinely ambiguous (two different customers, same amount) goes to a person
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1), inv("I2", "C2", "Bharat Textiles Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "9876543210@okhdfc", "UPI_ID", "UPI PAYMENT"), cr("T2", 9, "1000.00", "garbled name", "NEFT", "NEFT PAYMENT")),
                List.of(), List.of());
        check("unidentifiable payer is never auto-matched", r.outcomes.get("T2").decision != Decision.AUTO);

        // masked account resolved through master data (last 4 digits)
        r = run(CUSTOMERS, List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1)),
                List.of(cr("T1", 8, "1000.00", "XXXX4321", "IMPS", "IMPS PAYMENT")), List.of(), List.of());
        check("masked account matched via last-4", allocated(r, "T1", "I1") == Money.parse("1000.00"));

        // human correction teaches an alias that resolves later payments immediately
        var invs = List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1), inv("I2", "C1", "Acme Traders Pvt Ltd", "2500.00", 1));
        var txns = List.of(cr("T1", 8, "1000.00", "zz.unknown@exampleupi", "QR", "QR PAYMENT"), cr("T2", 15, "2500.00", "zz.unknown@exampleupi", "QR", "QR PAYMENT"));
        r = run(CUSTOMERS, invs, txns, List.of(), List.of());
        check("opaque handle alone is not trusted", r.outcomes.get("T2").decision != Decision.AUTO);
        r = run(CUSTOMERS, invs, txns, List.of(), List.of(new ManualDecision("T1", List.of(new Part("I1", Money.parse("1000.00"))))));
        check("manual decision applied", r.outcomes.get("T1").method == Method.MANUAL && allocated(r, "T1", "I1") == Money.parse("1000.00"));
        check("alias learned from the correction", r.learnedAliases.size() == 1);
        check("learned alias auto-matches the next payment", r.outcomes.get("T2").decision == Decision.AUTO && allocated(r, "T2", "I2") == Money.parse("2500.00"));

        // outstanding is derived and never negative
        check("outstanding derived", r.ledger.outstanding(r.ledger.get("I1")) == 0);
    }

    /** The Java feature list and the Python service must agree, or scores would be silently wrong. */
    static void featureParity() throws Exception {
        for (String p : List.of("../ml-service/app/features.py", "ml-service/app/features.py")) {
            Path path = Path.of(p);
            if (!Files.exists(path)) continue;
            Matcher m = Pattern.compile("\"([a-z0-9_]+)\"").matcher(Files.readString(path));
            List<String> py = new ArrayList<>();
            while (m.find()) py.add(m.group(1));
            check("Java and Python feature lists match", py.equals(Features.NAMES));
            return;
        }
        System.out.println("(feature parity check skipped: features.py not found)");
    }
}

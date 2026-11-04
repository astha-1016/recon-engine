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

    static Reconciler.Result run(Config cfg, Scorer scorer, List<Customer> cs, List<Invoice> invs, List<BankTxn> txns,
                                 List<ManualDecision> manual, List<CreditApplication> credits) {
        return new Reconciler(cfg, scorer).run(new Reconciler.Input(cs, invs, txns, List.of(), manual, credits));
    }

    /** Scores 0.95 whenever the amount is exact, whether or not the payer is known (to test policy switches). */
    static final Scorer AMOUNT_ONLY = new Scorer() {
        public double[] score(List<double[]> rows) {
            double[] out = new double[rows.size()];
            for (int i = 0; i < out.length; i++) out[i] = rows.get(i)[Features.AMOUNT_EXACT] == 1 ? 0.95 : 0.1;
            return out;
        }
        public String version() { return "amount-only"; }
    };

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
        duplicates();
        credits();
        strictPolicy();
        resolutionRounds();
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

    static void duplicates() {
        var invs = List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1000.00", 1), inv("I2", "C1", "Acme Traders Pvt Ltd", "2500.00", 1));
        // the same statement line imported twice: same bank reference and amount
        var twin1 = cr("A", 8, "1000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT");
        var twin2 = new BankTxn("B", d(8), twin1.amount(), Direction.CR, twin1.payer(), "NEFT", twin1.bankRef(), "", "NEFT PAYMENT");
        var r = run(CUSTOMERS, invs, List.of(twin1, twin2), List.of(), List.of());
        long rows = r.allocations.stream().filter(a -> a.invoiceId().equals("I1")).map(Allocation::txnId).distinct().count();
        check("a duplicated bank row is applied once, not twice", rows == 1 && r.allocations.stream().mapToLong(Allocation::amount).sum() == Money.parse("1000.00"));
        check("the duplicate is classified, never reconciled", r.outcomes.get("B").kind == TxnKind.DUPLICATE && r.outcomes.get("B").decision == Decision.CLASSIFIED);
        check("the duplicate points at its twin", r.outcomes.get("B").linkedTxnId.equals("A"));
        check("detector finds exactly one duplicate", DuplicateDetector.find(List.of(twin1, twin2)).size() == 1);
        // two genuinely different payments with the same amount but different references are NOT duplicates
        var other = new BankTxn("C", d(9), twin1.amount(), Direction.CR, twin1.payer(), "NEFT", "UTR-OTHER", "", "NEFT PAYMENT");
        check("same amount, different reference is not a duplicate", DuplicateDetector.find(List.of(twin1, other)).isEmpty());
        // same amount and reference but different amounts are not duplicates either
        var different = new BankTxn("D", d(8), Money.parse("1000.01"), Direction.CR, twin1.payer(), "NEFT", twin1.bankRef(), "", "NEFT PAYMENT");
        check("same reference, different amount is not a duplicate", DuplicateDetector.find(List.of(twin1, different)).isEmpty());
        // without a reference, an identical row (date, amount, payer, channel, narration) is a duplicate
        var n1 = new BankTxn("E", d(8), Money.parse("500.00"), Direction.CR, "Acme", "QR", "", "", "QR PAYMENT");
        var n2 = new BankTxn("F", d(8), Money.parse("500.00"), Direction.CR, "Acme", "QR", "", "", "QR PAYMENT");
        check("identical rows with no reference are duplicates", DuplicateDetector.find(List.of(n1, n2)).size() == 1);
    }

    static void credits() {
        var invs = List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1), inv("I2", "C1", "Acme Traders Pvt Ltd", "7000.00", 20));
        var pay = List.of(cr("T1", 8, "10100.00", "9876543210@oksbi", "IMPS", "IMPS PAYMENT"));
        var base = run(new Config(), STUB, CUSTOMERS, invs, pay, List.of(), List.of());
        check("overpayment leaves a credit", base.credits.size() == 1 && base.credits.get(0).amount() == Money.parse("100.00"));

        var applied = run(new Config(), STUB, CUSTOMERS, invs, pay, List.of(),
                List.of(new CreditApplication("T1", "I2", Money.parse("60.00"))));
        check("part of a credit can be applied to another invoice", allocated(applied, "T1", "I2") == Money.parse("60.00"));
        check("the credit shrinks by what was applied", applied.credits.size() == 1 && applied.credits.get(0).amount() == Money.parse("40.00"));
        check("the other invoice is now partly paid", applied.ledger.status(applied.ledger.get("I2")) == InvoiceStatus.PARTIALLY_PAID);
        check("applied credit is recorded as a manual allocation", applied.allocations.stream().anyMatch(a -> a.invoiceId().equals("I2") && a.method() == Method.MANUAL));

        var all = run(new Config(), STUB, CUSTOMERS, invs, pay, List.of(),
                List.of(new CreditApplication("T1", "I2", Money.parse("100.00"))));
        check("a fully used credit disappears", all.credits.isEmpty() && all.warnings.isEmpty());

        var tooMuch = run(new Config(), STUB, CUSTOMERS, invs, pay, List.of(),
                List.of(new CreditApplication("T1", "I2", Money.parse("100.01"))));
        check("cannot apply more credit than exists", allocated(tooMuch, "T1", "I2") == 0 && tooMuch.warnings.size() == 1);

        var gone = run(new Config(), STUB, CUSTOMERS, invs, pay, List.of(), List.of(new CreditApplication("NOPE", "I2", 100)));
        check("applying a credit that does not exist is refused", gone.warnings.size() == 1 && allocated(gone, "NOPE", "I2") == 0);

        var small = List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "10000.00", 1), inv("I2", "C1", "Acme Traders Pvt Ltd", "30.00", 20));
        var over = run(new Config(), STUB, CUSTOMERS, small, pay, List.of(), List.of(new CreditApplication("T1", "I2", Money.parse("50.00"))));
        check("cannot apply more than the invoice owes", allocated(over, "T1", "I2") == 0 && over.warnings.size() == 1);
    }

    static void strictPolicy() {
        var invs = List.of(inv("I1", "C1", "Acme Traders Pvt Ltd", "1234.56", 1));
        var txns = List.of(cr("T1", 8, "1234.56", "zz.unknown@exampleupi", "QR", "QR PAYMENT"));
        var normal = run(new Config(), AMOUNT_ONLY, CUSTOMERS, invs, txns, List.of(), List.of());
        check("default policy auto-matches a unique exact amount even for an unknown payer", normal.outcomes.get("T1").decision == Decision.AUTO);
        Config strict = new Config();
        strict.requireIdentityForAuto = true;
        var s = run(strict, AMOUNT_ONLY, CUSTOMERS, invs, txns, List.of(), List.of());
        check("strict policy sends an unidentified payer to review", s.outcomes.get("T1").decision == Decision.REVIEW && s.allocations.isEmpty());
        var known = List.of(cr("T1", 8, "1234.56", "9876543210@okhdfc", "QR", "QR PAYMENT"));
        var k = run(strict, AMOUNT_ONLY, CUSTOMERS, invs, known, List.of(), List.of());
        check("strict policy still auto-matches an identified payer", k.outcomes.get("T1").decision == Decision.AUTO);
        var withRef = List.of(cr("T1", 8, "1234.56", "zz.unknown@exampleupi", "QR", "QR PAYMENT INV-2026-00001"));
        var refInv = List.of(inv("INV-2026-00001", "C1", "Acme Traders Pvt Ltd", "1234.56", 1));
        var rr = run(strict, AMOUNT_ONLY, CUSTOMERS, refInv, withRef, List.of(), List.of());
        check("strict policy still auto-matches when the invoice number is quoted", rr.outcomes.get("T1").decision == Decision.AUTO);
    }

    /** Two customers share a business name, so an unreferenced payment is ambiguous until the other one is settled. */
    static void resolutionRounds() {
        var cs = List.of(new Customer("C1", "Acme Traders Pvt Ltd", "", ""), new Customer("C2", "Acme Traders Pvt Ltd", "", ""));
        var invs = List.of(inv("INV-2026-00001", "C1", "Acme Traders Pvt Ltd", "1000.00", 1), inv("INV-2026-00002", "C2", "Acme Traders Pvt Ltd", "1000.00", 1));
        var txns = List.of(cr("T1", 8, "1000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT"),
                           cr("T2", 12, "1000.00", "Acme Traders Pvt Ltd", "NEFT", "NEFT PAYMENT INV-2026-00002"));
        Config off = new Config();
        off.resolutionRounds = 0;
        var first = run(off, STUB, cs, invs, txns, List.of(), List.of());
        check("without rounds the ambiguous payment waits for a person", first.outcomes.get("T1").decision == Decision.REVIEW);
        check("the referenced payment is matched either way", allocated(first, "T2", "INV-2026-00002") == Money.parse("1000.00"));
        var second = run(new Config(), STUB, cs, invs, txns, List.of(), List.of());
        check("with rounds it resolves once the other invoice is taken", second.outcomes.get("T1").decision == Decision.AUTO
                && allocated(second, "T1", "INV-2026-00001") == Money.parse("1000.00"));
        check("the reason explains it was resolved in a later round", second.outcomes.get("T1").reason.contains("round 1"));
        check("rounds never touch what a person decided", run(new Config(), STUB, cs, invs, txns,
                List.of(new ManualDecision("T1", List.of(new Part("INV-2026-00002", Money.parse("1000.00"))))), List.of()).outcomes.get("T1").method == Method.MANUAL);
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

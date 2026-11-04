package com.reconcile.engine;

import com.reconcile.engine.Model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** CSV files on disk -> engine domain objects (+ ground truth, which only the evaluator and exporter may read). */
public final class DataSet {

    public record Truth(String txnId, String invoiceId, String customerId, long amount, String scenario,
                        String eventType, String split, String originalTxnId) {}

    public final List<Customer> customers = new ArrayList<>();
    public final List<Invoice> invoices = new ArrayList<>();
    public final List<BankTxn> txns = new ArrayList<>();
    public final List<Truth> truth = new ArrayList<>();

    public static DataSet load(Path dir) throws IOException {
        DataSet ds = new DataSet();
        for (Map<String, String> r : Csv.read(dir.resolve("invoices.csv")))
            ds.invoices.add(new Invoice(r.get("invoice_id"), r.get("customer_id"), r.get("customer_name"), Money.parse(r.get("amount")),
                    LocalDate.parse(r.get("invoice_date")), LocalDate.parse(r.get("due_date"))));
        for (Map<String, String> r : Csv.read(dir.resolve("bank_transactions.csv")))
            ds.txns.add(parseTxn(r));
        Path cust = dir.resolve("customers.csv");
        if (Files.exists(cust))
            for (Map<String, String> r : Csv.read(cust))
                ds.customers.add(new Customer(r.get("customer_id"), r.get("name"), r.getOrDefault("phone", ""), r.getOrDefault("account_last4", "")));
        Path gt = dir.resolve("ground_truth.csv");
        if (Files.exists(gt))
            for (Map<String, String> r : Csv.read(gt))
                ds.truth.add(new Truth(r.get("transaction_id"), r.get("invoice_id"), r.get("customer_id"), Money.parse(r.get("allocated_amount")),
                        r.get("scenario"), r.get("event_type"), r.get("evaluation_split"),
                        r.getOrDefault("original_transaction_id", "")));
        return ds;
    }

    public static BankTxn parseTxn(Map<String, String> r) {
        long amount = Money.parse(r.get("amount"));
        return new BankTxn(r.get("transaction_id"), LocalDate.parse(r.get("transaction_date")), amount,
                Direction.valueOf(r.get("direction").trim().toUpperCase()), r.getOrDefault("payer_name", ""),
                r.getOrDefault("channel", ""), r.getOrDefault("bank_reference", ""), r.getOrDefault("reversal_reference", ""),
                r.getOrDefault("narration", ""));
    }

    public Reconciler.Input input(List<Alias> aliases, List<ManualDecision> manual) {
        return new Reconciler.Input(customers, invoices, txns, aliases, manual);
    }

    /** Evaluation split of each transaction, taken from ground truth. */
    public Map<String, String> txnSplit() {
        Map<String, String> m = new HashMap<>();
        for (Truth t : truth) m.putIfAbsent(t.txnId(), t.split());
        return m;
    }
}

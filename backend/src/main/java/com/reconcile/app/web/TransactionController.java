package com.reconcile.app.web;

import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.app.service.RecordService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Bank transactions ("payments"): browse them, add one by hand, correct or delete a wrong row. */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    public record TxnRow(String id, String date, long amount, String direction, String payer, String channel, String bankRef,
                         String reversalRef, String narration, String status) {}

    private final TxnRepo txns;
    private final OutcomeRepo outcomes;
    private final RecordService records;

    public TransactionController(TxnRepo txns, OutcomeRepo outcomes, RecordService records) {
        this.txns = txns; this.outcomes = outcomes; this.records = records;
    }

    @GetMapping
    public List<TxnRow> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "300") int limit) {
        Map<String, String> status = new HashMap<>();
        for (OutcomeE o : outcomes.findAll()) status.put(o.txnId, o.decision);
        List<TxnRow> out = new ArrayList<>();
        for (TxnE t : txns.findAll()) {
            if (q != null && !q.isBlank()
                    && !(t.id + " " + n(t.payer) + " " + n(t.bankRef) + " " + n(t.narration)).toLowerCase().contains(q.toLowerCase())) continue;
            out.add(new TxnRow(t.id, t.txnDate.toString(), t.amount, t.direction, n(t.payer), n(t.channel), n(t.bankRef),
                    n(t.reversalRef), n(t.narration), status.getOrDefault(t.id, "")));
        }
        out.sort(Comparator.comparing(TxnRow::date).reversed().thenComparing(TxnRow::id));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Record one payment by hand (same validation as a bank-statement upload). */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody RecordService.TxnIn in, Authentication auth) {
        records.createTxn(in, auth.getName());
        return Map.of("status", "created");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> update(@PathVariable String id, @RequestBody RecordService.TxnIn in, Authentication auth) {
        records.updateTxn(id, in, auth.getName());
        return Map.of("status", "updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> delete(@PathVariable String id, Authentication auth) {
        records.deleteTxn(id, auth.getName());
        return Map.of("status", "deleted");
    }

    private static String n(String s) { return s == null ? "" : s; }
}

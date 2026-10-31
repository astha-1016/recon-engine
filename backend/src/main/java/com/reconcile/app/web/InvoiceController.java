package com.reconcile.app.web;

import com.reconcile.app.config.AppProperties;
import com.reconcile.app.domain.Entities.*;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.engine.Money;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    public record InvoiceView(String id, String customerId, String customerName, long amount, long paid, long adjusted, long outstanding,
                              String issueDate, String dueDate, String status) {}

    private final InvoiceRepo invoices;
    private final AllocationRepo allocations;
    private final AdjustmentRepo adjustments;
    private final AppProperties props;

    public InvoiceController(InvoiceRepo invoices, AllocationRepo allocations, AdjustmentRepo adjustments, AppProperties props) {
        this.invoices = invoices; this.allocations = allocations; this.adjustments = adjustments; this.props = props;
    }

    @GetMapping
    public List<InvoiceView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "300") int limit) {
        Map<String, Long> paid = new HashMap<>(), adj = new HashMap<>();
        for (AllocationE a : allocations.findAll()) paid.merge(a.invoiceId, a.amount, Long::sum);
        for (AdjustmentE a : adjustments.findAll()) adj.merge(a.invoiceId, a.amount, Long::sum);
        List<InvoiceView> out = new ArrayList<>();
        for (InvoiceE i : invoices.findAll()) {
            if (status != null && !status.isBlank() && !status.equals(i.status)) continue;
            if (q != null && !q.isBlank() && !(i.id + " " + i.customerName).toLowerCase().contains(q.toLowerCase())) continue;
            out.add(view(i, paid.getOrDefault(i.id, 0L), adj.getOrDefault(i.id, 0L)));
        }
        out.sort(Comparator.comparing(InvoiceView::dueDate).thenComparing(InvoiceView::id));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * Prevention for Problem 2: a UPI deep link (render it as a QR) that carries the invoice number in the
     * transaction note, so the bank statement later contains it and Tier 1 matching succeeds.
     */
    @GetMapping("/{id}/upi-link")
    public Map<String, String> upiLink(@PathVariable String id) {
        InvoiceE i = invoices.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice"));
        long paid = allocations.findByInvoiceId(id).stream().mapToLong(a -> a.amount).sum()
                + adjustments.findAll().stream().filter(a -> a.invoiceId.equals(id)).mapToLong(a -> a.amount).sum();
        long outstanding = Math.max(0, i.amount - paid);
        if (outstanding == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice has nothing outstanding");
        String link = "upi://pay?pa=" + enc(props.payeeVpa()) + "&pn=" + enc(props.payeeName()) + "&am=" + Money.format(outstanding)
                + "&cu=INR&tn=" + enc(i.id);
        return Map.of("link", link, "invoiceId", i.id, "amount", Money.format(outstanding));
    }

    private static InvoiceView view(InvoiceE i, long paid, long adj) {
        return new InvoiceView(i.id, i.customerId, i.customerName, i.amount, paid, adj, Math.max(0, i.amount - paid - adj),
                i.issueDate.toString(), i.dueDate.toString(), i.status);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}

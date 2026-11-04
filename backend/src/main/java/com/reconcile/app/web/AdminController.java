package com.reconcile.app.web;

import com.reconcile.app.domain.Entities.AuditE;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.app.service.ReconService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Admin-only data maintenance: see what is stored, and wipe demo data before real use. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    public record ResetRequest(String confirm) {}

    private final CustomerRepo customers;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final ReconService recon;
    private final AuditRepo audit;

    public AdminController(CustomerRepo customers, InvoiceRepo invoices, TxnRepo txns, ReconService recon, AuditRepo audit) {
        this.customers = customers; this.invoices = invoices; this.txns = txns; this.recon = recon; this.audit = audit;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return Map.of("customers", customers.count(), "invoices", invoices.count(), "transactions", txns.count());
    }

    /** Wipes customers, invoices, bank transactions, results, decisions and learned aliases. Users and the audit log stay. */
    @PostMapping("/reset")
    public Map<String, String> reset(@RequestBody ResetRequest in, Authentication auth) {
        if (in == null || !"RESET".equals(in.confirm()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type RESET to confirm");
        long c = customers.count(), i = invoices.count(), t = txns.count();
        recon.wipeBusinessData();
        AuditE a = new AuditE();
        a.entityType = "SYSTEM"; a.entityId = "business-data"; a.action = "RESET"; a.actor = auth.getName();
        a.detail = "removed " + c + " customers, " + i + " invoices, " + t + " transactions";
        audit.save(a);
        return Map.of("status", "reset");
    }
}

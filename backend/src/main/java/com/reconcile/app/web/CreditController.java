package com.reconcile.app.web;

import com.reconcile.app.service.CreditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credits")
public class CreditController {
    public record ApplyRequest(String invoiceId, String amount) {}

    private final CreditService credits;

    public CreditController(CreditService credits) { this.credits = credits; }

    @GetMapping
    public List<CreditService.CreditView> list() { return credits.list(); }

    @GetMapping("/applications")
    public List<CreditService.ApplicationView> applications() { return credits.applications(); }

    @PostMapping("/{txnId}/apply")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> apply(@PathVariable String txnId, @RequestBody ApplyRequest req, Authentication auth) {
        credits.apply(txnId, req.invoiceId(), req.amount(), auth.getName());
        return Map.of("status", "accepted");
    }

    @DeleteMapping("/applications/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public Map<String, String> remove(@PathVariable long id, Authentication auth) {
        credits.remove(id, auth.getName());
        return Map.of("status", "removed");
    }
}

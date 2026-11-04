package com.reconcile.app.web;

import com.reconcile.app.config.LoginThrottle;
import com.reconcile.app.domain.Entities.AuditE;
import com.reconcile.app.domain.Entities.UserE;
import com.reconcile.app.domain.Repos.*;
import com.reconcile.app.service.BackupService;
import com.reconcile.app.service.DecisionService;
import com.reconcile.app.service.ReconService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Admin-only data maintenance: see what is stored, and wipe demo data before real use. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    /** confirm must be the word RESET; password is the signed-in admin's own password. */
    public record ResetRequest(String confirm, String password) {}

    private final CustomerRepo customers;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final UserRepo users;
    private final ReconService recon;
    private final AuditRepo audit;
    private final BackupService backups;
    private final DecisionService decisions;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;

    public AdminController(CustomerRepo customers, InvoiceRepo invoices, TxnRepo txns, UserRepo users, ReconService recon, AuditRepo audit,
                           BackupService backups, DecisionService decisions, PasswordEncoder encoder, LoginThrottle throttle) {
        this.customers = customers; this.invoices = invoices; this.txns = txns; this.users = users; this.recon = recon; this.audit = audit;
        this.backups = backups; this.decisions = decisions; this.encoder = encoder; this.throttle = throttle;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return Map.of("customers", customers.count(), "invoices", invoices.count(), "transactions", txns.count());
    }

    /**
     * Wipes customers, invoices, bank transactions, results, decisions and learned aliases, and retires the training labels
     * that came from them. Users and the audit log stay. Needs the typed word AND the admin's password, and takes a backup
     * file first; if the backup cannot be written nothing is deleted.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody ResetRequest in, Authentication auth) {
        if (in == null || !"RESET".equals(in.confirm()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type RESET to confirm");
        String key = "reset:" + auth.getName();
        throttle.check(key);
        UserE me = users.findByUsername(auth.getName()).orElse(null);
        if (me == null || in.password() == null || !encoder.matches(in.password(), me.passwordHash)) {
            throttle.failure(key);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your password is wrong");
        }
        throttle.success(key);

        Path backup;
        try {
            backup = backups.snapshotBusinessData();
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not write a backup first, so nothing was deleted: " + e.getMessage());
        }

        long c = customers.count(), i = invoices.count(), t = txns.count();
        recon.wipeBusinessData();
        int mlFailures = decisions.retireAllFeedback();

        AuditE a = new AuditE();
        a.entityType = "SYSTEM"; a.entityId = "business-data"; a.action = "RESET"; a.actor = auth.getName();
        a.detail = "removed " + c + " customers, " + i + " invoices, " + t + " transactions; training labels retired; backup "
                + backup.getFileName() + (mlFailures > 0 ? "; ML service not reached for " + mlFailures + " transaction(s)" : "");
        audit.save(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "reset");
        out.put("backup", backup.getFileName().toString());
        out.put("mlFeedbackNotRemoved", mlFailures);
        return out;
    }
}

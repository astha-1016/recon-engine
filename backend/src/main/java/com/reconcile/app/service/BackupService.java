package com.reconcile.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.domain.Repos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Safety net taken automatically before the admin reset: every business table is written to a gzip-compressed JSON file in
 * the backup folder. It is a last-resort copy of what the reset deletes (not a full database dump; use scripts/backup.sh for
 * that), and the reset is refused if it cannot be written.
 */
@Service
public class BackupService {
    private final CustomerRepo customers;
    private final InvoiceRepo invoices;
    private final TxnRepo txns;
    private final AliasRepo aliases;
    private final AllocationRepo allocations;
    private final AdjustmentRepo adjustments;
    private final CreditRepo credits;
    private final OutcomeRepo outcomes;
    private final ManualRepo manual;
    private final CreditApplicationRepo creditApps;
    private final FeedbackRepo feedback;
    private final ObjectMapper mapper;
    private final String dir;

    public BackupService(CustomerRepo customers, InvoiceRepo invoices, TxnRepo txns, AliasRepo aliases, AllocationRepo allocations,
                         AdjustmentRepo adjustments, CreditRepo credits, OutcomeRepo outcomes, ManualRepo manual,
                         CreditApplicationRepo creditApps, FeedbackRepo feedback, ObjectMapper mapper,
                         @Value("${app.backup-dir:backups}") String dir) {
        this.customers = customers; this.invoices = invoices; this.txns = txns; this.aliases = aliases; this.allocations = allocations;
        this.adjustments = adjustments; this.credits = credits; this.outcomes = outcomes; this.manual = manual;
        this.creditApps = creditApps; this.feedback = feedback; this.mapper = mapper;
        this.dir = dir == null || dir.isBlank() ? "backups" : dir;
    }

    /** Writes the snapshot and returns its path. Throws if it could not be written completely. */
    public Path snapshotBusinessData() throws IOException {
        Path folder = Paths.get(dir);
        Files.createDirectories(folder);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path file = folder.resolve("pre-reset-" + stamp + ".json.gz");
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("createdAt", Instant.now().toString());
        snap.put("customers", customers.findAll());
        snap.put("invoices", invoices.findAll());
        snap.put("bankTransactions", txns.findAll());
        snap.put("payerAliases", aliases.findAll());
        snap.put("allocations", allocations.findAll());
        snap.put("adjustments", adjustments.findAll());
        snap.put("unappliedCredits", credits.findAll());
        snap.put("txnOutcomes", outcomes.findAll());
        snap.put("manualDecisions", manual.findAll());
        snap.put("creditApplications", creditApps.findAll());
        snap.put("feedbackLog", feedback.findAll());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            mapper.writeValue(out, snap);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { /* best effort: do not leave a half-written backup */ }
            throw e instanceof IOException io ? io : new IOException("Could not serialise the backup: " + e.getMessage(), e);
        }
        if (Files.size(file) == 0) throw new IOException("Backup file is empty");
        return file;
    }
}

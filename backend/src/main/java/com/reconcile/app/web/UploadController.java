package com.reconcile.app.web;

import com.reconcile.app.service.IngestService;
import com.reconcile.app.service.RowReader;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
public class UploadController {
    private final IngestService ingest;

    public UploadController(IngestService ingest) { this.ingest = ingest; }

    /** kind = invoices | transactions | customers; accepts .csv, .xlsx or .xls. */
    @PostMapping("/{kind}")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public IngestService.Report upload(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return ingest.ingest(kind, RowReader.read(file));
    }
}

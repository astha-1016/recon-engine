package com.reconcile.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.reconcile.app.service.MlClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import com.reconcile.app.config.AppProperties;

@RestController
@RequestMapping("/api")
public class MlController {
    private final MlClient ml;
    private final AppProperties props;

    public MlController(MlClient ml, AppProperties props) { this.ml = ml; this.props = props; }

    @GetMapping("/ml/models")
    public JsonNode models() { return call(() -> ml.models()); }

    /** Retrain on the base data plus all stored human feedback. The service promotes the new model only if it is better. */
    @PostMapping("/ml/retrain")
    @PreAuthorize("hasRole('ADMIN')")
    public JsonNode retrain(@RequestParam(defaultValue = "gb") String algorithm) { return call(() -> ml.train(algorithm)); }

    /** Measured results written by the evaluation tooling (docs/evaluation.json). */
    @GetMapping("/reports/evaluation")
    public ResponseEntity<String> evaluation() throws Exception {
        Path p = Path.of(props.evaluationFile());
        if (!Files.exists(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No evaluation.json yet. Run scripts/evaluate.sh");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Files.readString(p));
    }

    private interface Call { JsonNode get() throws Exception; }

    private JsonNode call(Call c) {
        if (!ml.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ML service is disabled (ML_ENABLED=false)");
        try {
            return c.get();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service error: " + e.getMessage());
        }
    }
}

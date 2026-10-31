package com.reconcile.app.service;

import com.reconcile.engine.HttpScorer;
import com.reconcile.engine.RuleScorer;
import com.reconcile.engine.Scorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Uses the ML service when reachable; falls back to the rule scorer so reconciliation never stops. */
public class FallbackScorer implements Scorer {
    private static final Logger log = LoggerFactory.getLogger(FallbackScorer.class);

    private final HttpScorer ml;
    private final RuleScorer rules = new RuleScorer();
    private boolean failed;

    public FallbackScorer(HttpScorer ml) {
        this.ml = ml;
        this.failed = ml == null;
    }

    @Override
    public double[] score(List<double[]> rows) throws Exception {
        if (!failed) {
            try {
                return ml.score(rows);
            } catch (Exception e) {
                failed = true;
                log.warn("ML service unavailable ({}); falling back to rule scorer for this run", e.getMessage());
            }
        }
        return rules.score(rows);
    }

    @Override
    public String version() {
        return failed ? "rules-fallback" : ml.version();
    }
}

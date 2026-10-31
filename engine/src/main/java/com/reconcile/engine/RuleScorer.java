package com.reconcile.engine;

import java.util.ArrayList;
import java.util.List;

/** Hand-weighted baseline used when no ML model is available (and as the "rules only" arm of the evaluation). */
public final class RuleScorer implements Scorer {
    @Override
    public double[] score(List<double[]> rows) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = one(rows.get(i));
        return out;
    }

    private double one(double[] f) {
        double amountSignal = Math.max(Math.max(f[Features.AMOUNT_EXACT], f[Features.FRAC_MATCH] * 0.9),
                Math.max(f[Features.SHORT_PAY] * 0.7, f[Features.OVER_PAY] * 0.6));
        double z = -4.0 + 3.0 * amountSignal + 2.0 * f[Features.IDENTITY] + 1.5 * Math.max(f[Features.EXACT_UNIQUE], f[Features.FRAC_UNIQUE])
                + 2.5 * f[Features.REF_HIT] + 1.0 * f[Features.ALIAS_HIT]
                - 0.3 * Math.max(0, f[Features.N_IDENTITY] - 1) - 0.02 * Math.max(0, f[Features.GAP_DAYS] - 30);
        return 1.0 / (1.0 + Math.exp(-z));
    }

    @Override
    public String version() { return "rules-v1"; }

    public static List<double[]> single(double[] row) {
        List<double[]> l = new ArrayList<>();
        l.add(row);
        return l;
    }
}

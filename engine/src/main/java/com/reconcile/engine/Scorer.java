package com.reconcile.engine;

import java.util.List;

/** Turns candidate feature vectors into P(this plan is the correct explanation of the payment). */
public interface Scorer {
    double[] score(List<double[]> rows) throws Exception;

    String version();
}

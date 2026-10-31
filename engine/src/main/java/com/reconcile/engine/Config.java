package com.reconcile.engine;

/** Tunable business rules. Defaults are conservative: a wrong auto-match costs more than a missed one. */
public final class Config {
    public double autoThreshold = 0.90;
    public double reviewThreshold = 0.60;
    /** Best candidate must beat the runner-up by this much to be auto-applied. */
    public double margin = 0.15;
    public double identityThreshold = 0.85;
    /** A shortfall up to this fraction of the balance is treated as bank fee / TDS and the invoice is closed. */
    public double shortPayTolerance = 0.02;
    /** An excess up to this fraction of the balance becomes an unapplied credit. */
    public double overPayTolerance = 0.02;
    public int dateWindowDays = 120;
    public int chequeExtraDays = 10;
    public int maxSubsetSize = 5;
    public int maxInvoicesPerSubset = 14;
    public int reversalWindowDays = 45;
    public boolean enableFuzzy = true;
    public boolean enableComposite = true;

    public static Config exactOnly() {
        Config c = new Config();
        c.enableFuzzy = false;
        c.enableComposite = false;
        return c;
    }
}

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
    /**
     * Strict policy: never auto-match a payment unless the payer was identified (alias, phone, last-4 or name)
     * or the invoice number was quoted. Matching on amount alone can be wrong by coincidence, so with this switch on
     * such payments go to a person instead. Costs some automation; see docs/EVALUATION notes.
     */
    public boolean requireIdentityForAuto = false;
    /**
     * After the first pass, payments still waiting for a person are re-examined against the updated ledger, because
     * invoices claimed by other payments no longer compete. Repeats until nothing new resolves, up to this many rounds.
     */
    public int resolutionRounds = 3;
    public boolean enableFuzzy = true;
    public boolean enableComposite = true;

    public static Config exactOnly() {
        Config c = new Config();
        c.enableFuzzy = false;
        c.enableComposite = false;
        return c;
    }
}

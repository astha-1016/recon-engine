package com.reconcile.engine;

import java.util.List;

/** Feature names shared with the Python ML service. Order does not matter on the wire (names are sent), but keep both sides in sync. */
public final class Features {
    private Features() {}

    public static final List<String> NAMES = List.of(
            "n_inv", "is_composite", "amount_exact", "amount_diff_abs_log", "amount_diff_pct",
            "ratio_out", "ratio_orig", "frac_err_log", "frac_match",
            "identity_score", "alias_hit", "n_identity_customers", "name_lev", "name_jw", "name_token",
            "kind_name", "kind_vpa", "kind_phone", "kind_mask", "phone_match", "last4_match",
            "ch_qr", "ch_upi", "ch_neft", "ch_imps", "ch_cheque", "ch_cash", "ch_other",
            "gap_days", "days_vs_due", "already_paid_frac", "prior_payments",
            "n_amount_consistent", "amount_unique", "n_exact_candidates", "exact_unique", "n_frac_candidates", "frac_unique", "from_global", "ref_hit", "fifo_rank",
            "short_pay", "over_pay", "pool_size_log");

    private static final java.util.Map<String, Integer> INDEX = new java.util.HashMap<>();

    static {
        for (int i = 0; i < NAMES.size(); i++) INDEX.put(NAMES.get(i), i);
    }

    public static void put(double[] row, String name, double value) { row[INDEX.get(name)] = value; }

    public static int idx(String name) {
        int i = NAMES.indexOf(name);
        if (i < 0) throw new IllegalArgumentException("Unknown feature " + name);
        return i;
    }

    public static final int N_AMOUNT_CONSISTENT = idx("n_amount_consistent");
    public static final int AMOUNT_UNIQUE = idx("amount_unique");
    public static final int EXACT_UNIQUE = idx("exact_unique");
    public static final int FRAC_UNIQUE = idx("frac_unique");
    public static final int AMOUNT_EXACT = idx("amount_exact");
    public static final int FRAC_MATCH = idx("frac_match");
    public static final int SHORT_PAY = idx("short_pay");
    public static final int OVER_PAY = idx("over_pay");
    public static final int IDENTITY = idx("identity_score");
    public static final int REF_HIT = idx("ref_hit");
    public static final int ALIAS_HIT = idx("alias_hit");
    public static final int N_IDENTITY = idx("n_identity_customers");
    public static final int GAP_DAYS = idx("gap_days");
    public static final int POOL_SIZE_LOG = idx("pool_size_log");
}

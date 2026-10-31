"""Feature contract with the Java engine (engine/.../Features.java). A test keeps the two lists identical."""

FEATURE_NAMES = [
    "n_inv", "is_composite", "amount_exact", "amount_diff_abs_log", "amount_diff_pct",
    "ratio_out", "ratio_orig", "frac_err_log", "frac_match",
    "identity_score", "alias_hit", "n_identity_customers", "name_lev", "name_jw", "name_token",
    "kind_name", "kind_vpa", "kind_phone", "kind_mask", "phone_match", "last4_match",
    "ch_qr", "ch_upi", "ch_neft", "ch_imps", "ch_cheque", "ch_cash", "ch_other",
    "gap_days", "days_vs_due", "already_paid_frac", "prior_payments",
    "n_amount_consistent", "amount_unique", "n_exact_candidates", "exact_unique",
    "n_frac_candidates", "frac_unique", "from_global", "ref_hit", "fifo_rank",
    "short_pay", "over_pay", "pool_size_log",
]

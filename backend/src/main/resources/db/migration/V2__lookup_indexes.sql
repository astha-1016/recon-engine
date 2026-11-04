-- Lookups the app does on every run and on every edit/decision check.
-- IF NOT EXISTS so the same file works on a fresh database and on one that existed before Flyway was added.
CREATE INDEX IF NOT EXISTS idx_allocations_txn     ON allocations (txn_id);
CREATE INDEX IF NOT EXISTS idx_allocations_invoice ON allocations (invoice_id);
CREATE INDEX IF NOT EXISTS idx_manual_txn          ON manual_decisions (txn_id);
CREATE INDEX IF NOT EXISTS idx_manual_invoice      ON manual_decisions (invoice_id);
CREATE INDEX IF NOT EXISTS idx_credit_apps_txn     ON credit_applications (txn_id);
CREATE INDEX IF NOT EXISTS idx_credit_apps_invoice ON credit_applications (invoice_id);
CREATE INDEX IF NOT EXISTS idx_feedback_txn        ON feedback_log (txn_id);

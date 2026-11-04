# What changed in this version

## Running it with real data
- **Add rows by hand**: Invoices → New invoice, Payments → Record payment (same validation as an upload).
- **Edit and delete** invoices and payments (edit: Admin/Accountant, delete: Admin). Rows used by a manual decision or credit application are locked until the decision is undone. Allocations re-run after every change; each change is audited.
- **Payments page**: browse bank-statement lines with their match result.
- **User management** (Settings, Admin): add/remove users, change role, set a password; everyone can change their own password (10+ characters). The last admin cannot be removed or demoted.
- **Remove demo data** (Settings, Admin): wipes business data after typing RESET; keeps users, audit log and models.
- **Flyway migrations** (`V1` baseline, `V2` indexes); existing databases are baselined, not recreated. `DDL_AUTO=validate` available once verified.
- **Backups**: `scripts/backup.*` and `scripts/restore.*`, plus `docs/OPERATIONS.md`.
- API error messages are now returned (`server.error.include-message`), so forms show why a request was refused.
- New tests: `RecordsAndUsersTest`.

## Matching quality
- **Resolution rounds**: payments still waiting are re-examined once other payments have claimed their invoices.
- **Training on realistic states** (two-stage `scripts/train.sh`): removes a train/serve mismatch in the candidate pools. The model service now also refuses a model trained on a different feature list and retrains instead of mis-scoring.
- **Duplicate bank-row detection**: the same credit imported twice is classified, never reconciled.
- **Strict identity policy** (optional, off): never auto-match an unidentified payer without a quoted invoice number.
- Measured: noisy-data recall 87.8% → 96.5% (held-out), 86.7% → 94.1% (unseen); clean 98.0% → 99.6%. See `EVALUATION.md`, including four ideas that did not help.

## Product
- **Undo a manual decision** (Decisions tab): returns the payment to the engine, withdraws the alias it taught, revokes its training label.
- **Apply an unapplied credit** to another invoice of the same customer (Credits tab), with removal.
- Run warnings on the Dashboard (for example a credit application that can no longer be applied).
- Reports page shows clean, noisy and harsh-noise results and how often the review queue's top suggestion is right.

## Engineering
- **CI** (`.github/workflows/ci.yml`): generator, engine, ML service, `mvn verify`, frontend build on every push.
- **Secrets**: `.env.example`; `REQUIRE_STRONG_SECRETS=true` makes the backend refuse placeholder secrets.
- **Tests**: engine 44 → 69 checks, generator 8 → 20, ML service 9 → 11; new backend `DecisionFlowTest`.
- `scripts/check.sh` no longer deletes `build/` (it used to wipe evaluation datasets).
- Evaluator counts a duplicate pair as interchangeable and reports review top-suggestion accuracy.

## After you copy these files in
1. `git status`: because line endings were normalised, many files show as modified. That is expected; commit it as one change.
2. Make sure the training data is committed: `git ls-files ml-service/data` must list `training_base.csv.gz`. If it prints nothing, run `git add -f ml-service/data/training_base.csv.gz`. (The copy in your earlier archive did not contain it, so a fresh clone could not train.)
3. Your existing models were trained before feature lists were recorded, so the service treats them as stale and retrains on startup. `docker compose down` then `docker compose up -d --build`.
4. Run `mvn verify`. The new backend code has not been compiled; send me the first errors if any.

## Delete these leftovers from your repo after copying
They were replaced by newer files:
```
git rm -f noisy-data-upgrade.zip scripts/apply_noisy_upgrade.py docs/NOISY_UPGRADE.md \
          docs/noisy_after.txt docs/noisy_after_unseen.txt docs/noisy_before_test.txt \
          docs/noisy_before_unseen.txt docs/noisy_results_retrained_model.txt
```
(`git rm` complains about a file that is already gone; that is harmless.) The old result files now live under `docs/results/history/`.

## Not done
- Per-bank statement parsers, TDS/GST handling, multi-currency, post-dated cheques, real-time Kafka/Redis. These remain future work.
- Bulk approve of review items and an audit-log page.

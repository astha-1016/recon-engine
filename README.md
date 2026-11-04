# Financial Reconciliation Engine

Matches incoming bank payments to open invoices automatically, sends only the real exceptions to a person, and learns from their corrections.

**Stack:** Java 17 + Spring Boot (Security/JWT, JPA/Hibernate, Apache POI) · PostgreSQL · Python FastAPI + scikit-learn ML service · React dashboard · Docker Compose · GitHub Actions CI.

## Run it

```bash
cp .env.example .env             # then edit it: set JWT_SECRET and SEED_PASSWORD (the backend will not start on placeholders)
docker compose up -d --build     # first start trains the initial model (about 1-2 minutes)
# open http://localhost:8081   users: admin / accountant / viewer   password: the SEED_PASSWORD you set
```

Just trying it on your own machine? `REQUIRE_STRONG_SECRETS=false docker compose up -d --build` runs on the placeholder secrets (password `change-me-now`) and only logs a warning. Never do that on anything reachable by other people.

Then in the UI: **Upload** `data-generator/sample/customers.csv`, `invoices.csv`, `bank_transactions.csv` → **Dashboard → Run reconciliation** → **Exceptions** to review.

`REQUIRE_STRONG_SECRETS` defaults to `true`: the backend refuses to start on placeholder secrets. Sign-in is throttled (5 wrong passwords lock that username for 15 minutes), and a role change, password change or deletion ends that user's sessions immediately.

## Using it with your own data

Everything is stored in PostgreSQL and survives restarts. To go from the demo to real use: **Settings → Remove demo data** (needs your password; a backup file is written to `backups/` first), add your staff under **Settings → Users**, then load your data with **Upload** or add single rows with **Invoices → New invoice** and **Payments → Record payment**. Wrong rows can be edited (Admin/Accountant) or deleted (Admin). Backups, restore and schema migrations are in [`docs/OPERATIONS.md`](docs/OPERATIONS.md).

## What is in the box

| Folder | What it is |
|---|---|
| `engine/` | The matching logic, plain Java with **no framework dependencies**: normalisation, channel classifier, identity resolution, candidate generation, reversal linking, duplicate detection, ledger state machine, iterative resolution, scorers, evaluator, CLI. |
| `backend/` | Spring Boot API: CSV/Excel ingestion, JWT roles, persistence, exceptions queue, manual decisions with **undo**, **unapplied-credit application**, ML proxy. |
| `ml-service/` | FastAPI: `/score`, `/train`, `/feedback`, `/models`, model registry, calibration, promote-only-if-better, rejects models trained on an older feature set. |
| `frontend/` | React dashboard: KPIs, side-by-side review, manual split, credits, decisions/undo, upload, invoices with pay-QR, reports. |
| `data-generator/` | Synthetic ledger + bank statements **with ground truth**: base kit, v2 scenarios (short-pay, overpay, cheque return, phone VPAs), v3 noise (typos, dropped invoice numbers, date jitter, deducted bank fees, amount collisions, duplicate rows). |
| `docs/` | `INTERVIEW_GUIDE.md`, `ARCHITECTURE.md`, `EVALUATION.md`, `CHANGES.md`, `evaluation.json`, `results/`. |

## How a payment is reconciled

```
ingest → normalise → detect duplicate rows → classify channel & kind (payment / refund / reversal / cheque return / fee)
 → link reversals to the original payment
 → resolve payer identity: learned alias → master data (phone in UPI handle, account last-4) → fuzzy name
 → generate candidate plans (single invoice, partial, short-pay, overpay, composite many-invoices)
 → Tier 1  invoice reference + exact amount ........ confidence 1.0, auto
 → Tier 2  ML probability for each plan ............ ≥0.90 auto · 0.60–0.90 review · <0.60 unmatched
 → Tier 3  composite (bounded subset-sum, ≤5 invoices) scored by the same model
 → resolution rounds: payments still waiting are re-examined once other payments have claimed their invoices
 → allocate against OUTSTANDING balance, update the invoice state machine
 → exceptions dashboard → human decision (undoable) → alias learned + labelled training row
```

## Measured results

Synthetic data, **held-out customers and datasets the model never saw**. Precision is the number to watch: a wrong auto-match costs more than a missed one.

| Data | Pipeline | Precision | Recall | Auto-matched |
|---|---|---|---|---|
| Clean, held-out test customers | Exact only (reference + amount) | 100% | 16.5% | 17.2% |
| | + fuzzy + composite, rule scorer | 100% | 29.4% | 30.7% |
| | **Full pipeline (ML + resolution rounds)** | **100%** | **99.6%** | 99.6% |
| Clean, unseen seed | Full pipeline | 100% | 99.0% | 99.0% |
| Noisy, held-out test customers | Exact only | 100% | 4.7% | 4.9% |
| | + fuzzy + composite, rule scorer | 100% | 11.8% | 12.3% |
| | **Full pipeline** | **100%** | **96.5%** | 96.3% |
| Noisy, unseen seed | Full pipeline | 99.9% (1 wrong of 1,168) | 94.1% | 93.9% |
| Harsher noise (2x), unseen seed | Full pipeline | 99.9% (1 wrong of 953) | 76.8% | 75.8% |

Of the payments sent to review on noisy unseen data, **51 of 58 (88%) already had the right answer as the top suggestion**, so most need a click, not an investigation.

**What changed in this version** (same noisy data, before → after): recall 87.8% → 96.5% on held-out customers, 86.7% → 94.1% on the unseen seed; fee-deducted payments 26% → 70%; final invoice-status accuracy 84.5% → 95.5%. Two causes: iterative resolution rounds, and training on the states the model actually sees at run time. See `docs/EVALUATION.md` for the full story, including what did **not** work.

Still the weakest: short-pay (47% recall on the unseen seed, 30 cases) and overpayment (50%, 20 cases). The reasons are in `docs/EVALUATION.md`.

**Read these numbers honestly.** They come from synthetic data in which amounts are exact to the paisa and payers follow fixed patterns. Real statements are noisier, so expect lower recall; precision held because the engine abstains when unsure.

## What has and has not been verified

`scripts/check.sh` (no Docker needed): 20 generator tests, 69 engine checks, 11 ML-service tests, frontend type-check and build. All passed when this version was packaged.

**Not run:** the new backend code (undo, credit application, secrets check, `DecisionFlowTest`) was syntax-checked but never compiled or executed, because it was written where Maven Central is unreachable. `mvn verify` is the first thing to run; CI runs it on every push. Everything else in `backend/` ran earlier on a real machine.

## Commands

```bash
bash scripts/check.sh            # all tests that need no Docker
bash scripts/train.sh            # rebuild training data (two stages) and retrain; promoted only if better
bash scripts/evaluate.sh         # regenerate docs/evaluation.json from the running ML service
mvn verify                       # engine + backend (needs Maven Central)
java -cp build/classes com.reconcile.engine.Cli eval --data build/noisy42 --mode ml --ml http://localhost:8000 --split test
#   options: --auto 0.95 (stricter threshold)  --strict true (never auto-match unidentified payers)  --rounds 0 (no resolution rounds)
```

Windows: run the scripts in Git Bash. If a container exits with code 137, give Docker Desktop more memory (6 GB).

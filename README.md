# Financial Reconciliation Engine

Matches incoming bank payments to open invoices automatically, sends only the real exceptions to a person, and learns from their corrections.

**Stack (as proposed):** Java 17 + Spring Boot (Security/JWT, JPA/Hibernate, Apache POI) · PostgreSQL · Python FastAPI + scikit-learn ML service · React dashboard · Docker Compose.

## Run it

```bash
docker compose up --build        # first start trains the initial model (~1-2 min)
# open http://localhost:8081   users: admin / accountant / viewer   password: SEED_PASSWORD (default change-me-now)
```

Then in the UI: **Upload** `data-generator/sample/customers.csv`, `invoices.csv`, `bank_transactions.csv` → **Dashboard → Run reconciliation** → **Exceptions** to review.

## What is in the box

| Folder | What it is |
|---|---|
| `engine/` | The matching logic, written as plain Java with **no framework dependencies**, so it is fast to test and reusable. Normalisation, channel classifier, identity resolution, candidate generation, reversal linking, ledger state machine, scorers, evaluator, CLI. |
| `backend/` | Spring Boot API around the engine: CSV/Excel ingestion, JWT roles, persistence, exceptions queue, human decisions, ML proxy. |
| `ml-service/` | FastAPI: `/score`, `/train`, `/feedback`, `/models`, model registry, calibration, promote-only-if-better. |
| `frontend/` | React dashboard: KPIs, side-by-side review, manual split, upload, invoices with pay-QR, reports. |
| `data-generator/` | Synthetic ledger + bank statements **with ground truth** (your base generator, extended). |
| `docs/` | `INTERVIEW_GUIDE.md`, `ARCHITECTURE.md`, `evaluation.json` (measured results the dashboard shows). |

## How a payment is reconciled

```
ingest → normalise → classify channel & kind (payment / refund / reversal / cheque return / bank fee)
 → link reversals to the original payment
 → resolve payer identity: learned alias → master data (phone in UPI handle, account last-4) → fuzzy name
 → generate candidate plans (single invoice, partial, short-pay, overpay, composite many-invoices)
 → Tier 1  invoice reference + exact amount ........ confidence 1.0, auto
 → Tier 2  ML probability for each plan ............ ≥0.90 auto · 0.60–0.90 review · <0.60 unmatched
 → Tier 3  composite (bounded subset-sum, ≤5 invoices) scored by the same model
 → allocate against OUTSTANDING balance, update invoice state machine
 → exceptions dashboard → human decision → alias learned + labelled training row
```

## The 5 QR problems and where each is solved

| Problem | Where |
|---|---|
| Not all payments are QR | `Classifier` infers channel/kind from any bank row; the engine reconciles the bank record, not the method; cheques get a wider date window |
| QR doesn't say which invoice | Prevention: `/api/invoices/{id}/upi-link` makes a UPI QR carrying the invoice number. Detection: amount + identity + date scoring, composite matching, FIFO tie-break |
| Partial payments | `Ledger` state machine OPEN→PARTIALLY_PAID→PAID; matching is against *outstanding*; ≤2% shortfall closes as a bank-fee/TDS adjustment; overpayment becomes an unapplied credit |
| UPI handle / masked account instead of name | `IdentityResolver`: alias table (learned from human confirmations), phone and account-last-4 from `customers.csv`, abbreviation-aware fuzzy names |
| Refunds / reversals | `ReversalLinker` pairs by reference or infers by amount/date/payer; allocation is reversed; invoice → REFUNDED / DISPUTED / reopened on cheque return |

## Measured results (synthetic data, customer-disjoint test split)

| Pipeline | Precision | Recall | Auto-matched | Manual work left |
|---|---|---|---|---|
| Exact only (reference + amount) | 100% | 16.5% | 17.2% | 82.8% |
| + fuzzy + composite, rule scorer | 100% | 29.4% | 30.7% | 69.3% |
| **Full pipeline, ML scorer** | **100%** | **98.0%** | **98.0%** | **2.0%** |
| Full pipeline on a never-seen dataset (new seed) | 100% | 97.3% | 97.1% | 2.9% |

Weakest scenarios: **short-pay** (33% recall on the test split, 6 cases; 47% on the unseen seed, 30 cases) and **overpayment** (100% on 5 test cases, but 55% of 20 on the unseen seed). These need more training data or a stricter policy. Full per-scenario/per-channel tables: `docs/evaluation.json` and the Reports page.

**Read these numbers honestly.** They come from synthetic data in which amounts are exact to the paisa and payers follow fixed patterns. Real statements are noisier. Precision stayed at 100% because the engine abstains when unsure, but recall on real data will be lower. See `docs/INTERVIEW_GUIDE.md` §Limitations.

## What has and has not been verified

Run `scripts/check.sh` (no Docker needed): generator tests (8), engine self-test (44 checks), ML service tests (9), frontend type-check + build. All passed when this was packaged.

**Not verified:** the Spring Boot module (`backend/`) and Docker Compose were written without access to Maven Central or Docker, so they have **not been compiled or run**. The engine they wrap is tested; the glue (controllers, JPA entities, security config) is not. Expect to fix a few compile or wiring errors on first `mvn verify` / `docker compose up`. `backend/src/test/.../ApiSmokeTest.java` is the first thing to run.

## Commands

```bash
scripts/check.sh                 # all tests that need no Docker
scripts/evaluate.sh              # regenerate docs/evaluation.json from the running ML service
scripts/train.sh                 # rebuild training set + retrain (promotes only if better)
mvn verify                       # backend + engine (needs Maven Central)
java -cp <classes> com.reconcile.engine.Cli eval --data data-generator/sample --mode rules --split test
```

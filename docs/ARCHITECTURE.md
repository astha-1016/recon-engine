# Architecture and design decisions

## Components

```
React dashboard ──REST/JWT──► Spring Boot ──┬─► PostgreSQL
                                            ├─► engine (plain Java library: the matching logic)
                                            └─► FastAPI ML service (/score /train /feedback /models)
```
The engine is a separate Maven module with zero dependencies. The backend loads rows from the database, calls `Reconciler.run`, and stores the result. This keeps the interesting logic testable with plain `javac` and independent of Spring.

## Why a tiered, candidate-based design

Every incoming payment is turned into a small set of **candidate plans**: "this payment pays invoice A in full", "…40% of invoice B", "…invoices C+D+E together", "…invoice F short by 1% (write off the rest)". Each plan gets a feature vector, and one model scores P(plan is correct). That makes partial, short-pay, overpay and composite cases the same problem instead of four special cases.

* **Blocking** keeps it cheap: only invoices of plausible customers, or whose balance is consistent with the amount, are considered; composite search is a bounded subset-sum (≤5 invoices, ≤14 per customer).
* **Tier 1** is deterministic (reference + exact amount) so the easy majority is fully explainable.
* **Thresholds** are asymmetric on purpose: auto ≥0.90 *and* a 0.15 margin over the runner-up. A wrong auto-match is worse than a missed one.
* **Ties:** if the top candidates are interchangeable (same customer, same amount) the oldest due date wins (FIFO); anything else ambiguous goes to a person. Never random.

## Resolution rounds (iterative resolution)
Reconciling strictly in date order has a flaw: an unreferenced payment from a business name shared by several customers can look equally good for several invoices, because the other customers' payments have not been processed yet. After the first pass, payments still waiting are re-examined against the updated ledger (invoices that other payments have since claimed no longer compete), repeating while anything new resolves (default 3 rounds, `Config.resolutionRounds`). Every decision still has to clear the same 0.90 / margin bar, and a person's decision is never touched. Measured alone, with the same model: +1.4 points recall on noisy data.

## Duplicate bank rows
`DuplicateDetector` flags a credit that repeats an earlier one: same amount and bank reference, or (with no reference) same date, payer, channel and narration. Duplicates are classified, never reconciled, and excluded from reversal linking. Which of two identical rows is "the duplicate" is arbitrary, so the evaluator accepts either.

## Unapplied credits and undo
Overpayment surplus is kept as an unapplied credit and is never applied automatically. A person can put part of it against another open invoice **of the same customer** (`CreditApplication`); applications are stored, replayed on every run, validated again each time (credit left, invoice owes), and removable. Likewise a manual decision can be undone: the payment returns to the engine, the alias it taught loses a confirmation (deleted at zero), its feedback rows are marked revoked and deleted from the ML service's pending training data.

## Training the model (two stages)
Candidate rows are exported by running the engine over generated datasets, with ground truth used **only for labels**. Stage 1 follows the rule scorer. Stage 2 re-exports following the trained model's own decisions, including payments re-examined in later rounds. This matters: under the rule scorer almost nothing resolves, so candidate pools stay large; the real model closes invoices as it goes, so pools are smaller at run time. Training on states the model will actually see was worth about +7 points of recall on noisy data. `scripts/train.sh` does both stages and the service promotes the result only if it beats the active model on the same validation holdout. The service also refuses to use a model whose feature list differs from the engine's, and retrains on startup instead.

## Policy switches (all in `Config`)
`autoThreshold` (0.90) and `reviewThreshold` (0.60); `margin` (0.15 over the runner-up); `shortPayTolerance` / `overPayTolerance` (2%); `resolutionRounds` (3); `requireIdentityForAuto` (off). The last one never auto-matches a payment whose payer is unidentified and whose invoice number is not quoted. It targets amount-coincidence errors but costs about 10 points of recall and did not reliably remove errors (see `docs/EVALUATION.md`), so it is off by default.

## Money
All amounts are `long` paise in Java and the database. Parsing goes through `BigDecimal`. Outstanding is **derived** (`amount − cash − adjustments`) and never edited directly.

## Invoice state machine
`OPEN → PARTIALLY_PAID → PAID`, and from PAID: refund → `REFUNDED`, bank reversal / partial refund → `DISPUTED`, bounced cheque → back to `OPEN`.

## Identity resolution (problem 4)
A UPI handle such as `9876543210@okhdfc` shares no text with "ABC Traders", so string similarity cannot link them. Layers, in order: (1) alias confirmed by a person before, (2) master data: phone number inside the handle, last-4 of a masked account, (3) fuzzy business-name match with abbreviation expansion (`TRD`→traders, `P LTD` dropped). Opaque handles with no evidence resolve to nobody, and are then only matched when the *amount* is unambiguous.

## Feedback loop
A decision does three things (and undo reverses all three): it is honoured on every later run; it adds an alias immediately (instant, narrow learning); and it becomes labelled rows (chosen plan = 1, other suggestions = 0) sent to `/feedback` (slow, general learning). `/train` retrains on base data + feedback, evaluates on a fixed validation holdout, **re-scores the active model on that same holdout**, and promotes only if better. Any version can be re-activated (rollback).

## ML details
`HistGradientBoostingClassifier` (or random forest), isotonic/sigmoid calibration with grouped CV (groups = transaction, so one payment's candidates never straddle train and validation), metrics: average precision, ROC-AUC, log-loss, Brier, precision/recall at the 0.90 auto threshold. The 44 features are listed in `Features.java` and mirrored in `features.py`; a test fails if they drift apart.

## Data model (JPA entities, `domain/Entities.java`)
customers · payer_aliases · invoices (status derived) · bank_transactions (signed amount, direction) · allocations (signed; negatives are reversals) · adjustments (short-pay write-offs) · unapplied_credits · txn_outcomes (decision, reason, top-3 candidates as JSON) · manual_decisions · feedback_log · app_users · audit_log. Hibernate creates the schema (`ddl-auto: update`); for production, pin it with Flyway migrations.

## Security
Stateless JWT (HS256). Roles: ADMIN (all, retrain), ACCOUNTANT (upload, decide, run), VIEWER (read). Change `JWT_SECRET` and `SEED_PASSWORD` before any real use.

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

## Money
All amounts are `long` paise in Java and the database. Parsing goes through `BigDecimal`. Outstanding is **derived** (`amount − cash − adjustments`) and never edited directly.

## Invoice state machine
`OPEN → PARTIALLY_PAID → PAID`, and from PAID: refund → `REFUNDED`, bank reversal / partial refund → `DISPUTED`, bounced cheque → back to `OPEN`.

## Identity resolution (problem 4)
A UPI handle such as `9876543210@okhdfc` shares no text with "ABC Traders", so string similarity cannot link them. Layers, in order: (1) alias confirmed by a person before, (2) master data: phone number inside the handle, last-4 of a masked account, (3) fuzzy business-name match with abbreviation expansion (`TRD`→traders, `P LTD` dropped). Opaque handles with no evidence resolve to nobody, and are then only matched when the *amount* is unambiguous.

## Feedback loop
A decision does three things: it is honoured on every later run; it adds an alias immediately (instant, narrow learning); and it becomes labelled rows (chosen plan = 1, other suggestions = 0) sent to `/feedback` (slow, general learning). `/train` retrains on base data + feedback, evaluates on a fixed validation holdout, **re-scores the active model on that same holdout**, and promotes only if better. Any version can be re-activated (rollback).

## ML details
`HistGradientBoostingClassifier` (or random forest), isotonic/sigmoid calibration with grouped CV (groups = transaction, so one payment's candidates never straddle train and validation), metrics: average precision, ROC-AUC, log-loss, Brier, precision/recall at the 0.90 auto threshold. The 44 features are listed in `Features.java` and mirrored in `features.py`; a test fails if they drift apart.

## Data model (JPA entities, `domain/Entities.java`)
customers · payer_aliases · invoices (status derived) · bank_transactions (signed amount, direction) · allocations (signed; negatives are reversals) · adjustments (short-pay write-offs) · unapplied_credits · txn_outcomes (decision, reason, top-3 candidates as JSON) · manual_decisions · feedback_log · app_users · audit_log. Hibernate creates the schema (`ddl-auto: update`); for production, pin it with Flyway migrations.

## Security
Stateless JWT (HS256). Roles: ADMIN (all, retrain), ACCOUNTANT (upload, decide, run), VIEWER (read). Change `JWT_SECRET` and `SEED_PASSWORD` before any real use.

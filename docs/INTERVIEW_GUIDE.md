# Interview guide

## 30-second pitch
> "Finance teams spend days matching bank transactions to invoices by hand, because payments never arrive clean: a QR payment carries no invoice number, customers pay in instalments or for several invoices at once, the bank shows a UPI handle instead of a name, and refunds come back as unexplained debits. We built a reconciliation engine: a deterministic tier for the clean cases, an ML-scored tier for ambiguous ones, bounded subset-sum for bundled payments, a payer-alias memory that grows from human corrections, an invoice state machine for partial payments, and reversal linking for refunds. Only genuine exceptions reach a person. On held-out synthetic customers it auto-matched 98% of payments at 100% precision, versus 17% for exact-matching alone."

## Numbers you can quote (they are measured; regenerate with `scripts/evaluate.sh`)
* Exact-only → rules → full pipeline recall on held-out customers: **16.5% → 29.4% → 98.0%**, precision 100% each time.
* New dataset the model never saw: **97.3% recall, 100% precision**.
* Manual work left: **82.8% → 2.0%** of invoice payments.
* The tier comparison is your strongest evidence that each part earns its place. Say it that way.

## What to say about the hard parts
1. **Candidate plans, one model.** Partial, short-pay, overpay and composite are all "a plan with allocations"; one calibrated classifier scores them.
2. **A subtle bug we found by measuring:** a 2% short-pay tolerance made ~60 invoices "amount-consistent" with every payment, which destroyed the uniqueness signal. We split the features into exact-amount, fraction-amount and loose counts. *Telling a story about a bug you found with data is worth more than a clean story.*
3. **A flaw in our own retraining logic:** we first compared a new model's holdout score with the old model's *stored* score; if the data changes those aren't comparable. We now re-score the active model on the current holdout.
4. **Identity resolution is layered because string similarity can't link a phone-number handle to a business name.** Be precise: the model does not "learn the handle"; the alias table gives instant learning and retraining gives slow, general learning.
5. **Scale:** blocking by identity and amount instead of O(n×m); bounded subset size.
6. **Money correctness:** paise as `long`, derived balances, audit log, every match reversible.

## Limitations (say these before they ask)
* **Synthetic data.** Amounts are exact to the paisa; payment lag and instalment fractions follow fixed patterns. Ablation: removing date-gap and amount-uniqueness features barely changed accuracy, because exact-amount and fraction matching already separate the classes. Real statements have noise; expect lower recall, and re-tune thresholds on real data.
* **Weak spots:** short-pay (33% recall on test, 47% on the unseen seed) and overpayment (100% of 5 on test, 55% of 20 on unseen). Too few training examples and genuine ambiguity among similar invoices.
* **Learning curve is modest here.** After 100 simulated corrections the queue dropped 169→63, but corrections + queue stayed ≈163, because few payers repeat in this data. The alias benefit is real for recurring customers; the dataset just doesn't show it.
* **Not verified end-to-end:** the Spring Boot layer and Docker Compose were not compiled or run when this was packaged. Say what you ran yourselves.
* Bank descriptions differ by bank; real parsers need per-bank rules. Kafka/Redis/WebSockets are deliberately future work.

## Likely questions
1. **Two invoices match equally well?** Same customer and amount → oldest due date (FIFO). Different customers → review queue. Never random; auto needs a 0.15 margin.
2. **Why gradient boosting, not deep learning?** Tabular features, ~300k rows, need calibrated probabilities and explainability; trees do that cheaply.
3. **Leakage?** Splits are by customer; ground truth is read only for labels and evaluation; CV groups are by transaction; a fresh seed is the final check; seed-42 test customers were never trained on.
4. **User rejects an auto-match?** Reject → negative training labels; assignment → manual decision honoured on every run, allocations are signed so reversal is exact.
5. **1M transactions a month?** Process incrementally by date (the engine already runs in date order), index outstanding invoices by customer and amount, batch scoring, run reconciliation as a queued job, shard by business.
6. **Why a Python service instead of embedding the model in Java?** The ML ecosystem and retraining live in Python; the engine only depends on a tiny `Scorer` interface and falls back to rules if the service is down.
7. **How do you know it works on real data?** We don't yet. Pilot in shadow mode on one month of real statements, compare to what accountants did, and tune thresholds for precision first.

## Demo script (5 minutes)
Upload the three sample files → Run reconciliation → Dashboard KPIs → Exceptions: open a review item, show the top-3 suggestions with scores, approve one → Invoices: show a pay-QR with the invoice number in the UPI note → Reports: the tier comparison table and the scenario chart → ML models panel: retrain, point out "promoted only if better".

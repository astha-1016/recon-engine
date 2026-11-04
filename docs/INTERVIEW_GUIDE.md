# Interview guide

## 30-second pitch
> "Finance teams spend days matching bank transactions to invoices by hand, because payments never arrive clean: a QR payment carries no invoice number, customers pay in instalments or for several invoices at once, the bank shows a UPI handle instead of a name, fees get deducted, rows are duplicated, and refunds come back as unexplained debits. We built a reconciliation engine: a deterministic tier for the clean cases, an ML-scored tier for ambiguous ones, bounded subset-sum for bundled payments, iterative resolution so payments that look ambiguous get another look once others are settled, a payer-alias memory that grows from human corrections, an invoice state machine for partial payments, and reversal linking for refunds. Only genuine exceptions reach a person, and for most of those the top suggestion is already right. On held-out synthetic customers it auto-matched 99.6% of clean payments at 100% precision, and 96.5% of payments after we added realistic noise, still at 100% precision."

## Numbers you can quote (measured; regenerate with `scripts/evaluate.sh`)
* Clean, held-out customers: **99.6% recall, 100% precision**. Exact-only matching alone gets 16.5%.
* Realistic noise, held-out customers: **96.5% / 100%**; unseen noisy dataset: **94.1% / 99.9%** (one wrong match in 1,168); harsher noise: **76.8% / 99.9%**.
* Before and after this version on the same noisy data: 87.8% → 96.5% and 86.7% → 94.1%.
* Review queue: the top suggestion was right for **51 of 58 items (88%)**, so most need one click.
* Tests: 20 generator, 69 engine, 11 ML service; CI runs them on every push.

## Stories that show you measured instead of assumed
1. **The 2% short-pay tolerance** made ~60 invoices look amount-consistent with every payment and destroyed the uniqueness signal. Found by inspecting candidates; fixed with separate exact / fraction / loose counts.
2. **Comparing models on different holdouts** was a flaw in our own retraining logic: the new model's score was compared with the old model's *stored* score. Now the active model is re-scored on the current holdout.
3. **The alias bug found by clicking.** One manual approval of a plain business name made the engine drop the other four customers sharing that name, and auto-match fell from 97.3% to 95.6%. Unit tests missed it because they only used opaque UPI handles. Fixed: aliases are exclusive only for opaque payers.
4. **A hypothesis that was wrong.** I expected that features describing how well each payment fits each invoice would fix fee-deducted payments. Retraining showed no gain; looking at the data showed why (the true invoice averages a rank in the high 50s by amount closeness because the fee is as large as the natural spread of invoice amounts). I removed the features. Saying "we tested it, it did not help, and here is why" is stronger than a clean story.
5. **A train/serve mismatch.** Training rows were exported while the rule scorer drove the run, which resolves almost nothing, so candidate pools were larger than at run time. Re-exporting along the model's own decisions was worth +7 points of recall on noisy data.
6. **An evaluation artifact.** A "wrong auto-match" turned out to be two identical bank rows: the money was applied once, to the right invoice, on the other row. The evaluator now treats identical rows as interchangeable.

## What to say about the hard parts
* **Candidate plans, one model.** Partial, short-pay, overpay and composite are all "a plan with allocations"; one calibrated classifier scores them.
* **Calibration is checked, not assumed.** Predicted probability matches observed accuracy in every band, so the review band (0.6-0.9) really is right about 89% of the time.
* **Iterative resolution.** Strict date-order processing leaves payments ambiguous because the competing customers' payments come later. After the first pass, waiting payments are re-examined against the updated ledger.
* **Identity resolution is layered** because string similarity cannot link a phone-number handle to a business name: alias, master data (phone, account last-4), fuzzy name.
* **Money correctness:** paise as `long`, derived balances, audit log, every decision undoable.
* **Safety valves:** thresholds with a margin over the runner-up, FIFO only among interchangeable invoices, duplicate detection, a strict-identity policy switch, and the model service refusing a model trained on a different feature set.

## Limitations (say these before they ask)
* **Synthetic data.** Amounts exact to the paisa, fixed payment lags, flat ₹10-50 fees. Real statements are noisier; expect lower recall and re-tune thresholds. The honest plan is shadow mode on a month of real statements.
* **Weak spots:** short-pay (47% recall on the unseen seed) and overpayment (50%). When several customers share a business name and the deducted fee is as large as the spread of invoice amounts, the data simply does not identify the invoice. Those go to review, where the top suggestion is usually right.
* **Precision is 99.9%, not 100%, on the harder data.** One wrong match in about 1,170; one case was an amount coincidence. A strict policy exists but costs about 10 points of recall; we measured it and left it off.
* **Small held-out scenario counts** (6 short-pay, 5 overpay); quote the unseen-seed counts for those.
* **Backend additions not run in this environment:** the undo / credit code and its tests were written without access to Maven Central. Say what you ran yourselves.
* Bank descriptions differ by bank; real parsers need per-bank rules. Kafka/Redis/WebSockets are future work.

## Likely questions
1. **Two invoices match equally well?** Same customer and amount → oldest due date (FIFO). Different customers → review queue, and after the first pass it is re-examined once the other customer's payment has claimed its invoice. Never random; auto needs a 0.15 margin.
2. **Why gradient boosting, not deep learning?** Tabular features, a few hundred thousand rows, need calibrated probabilities and explainability.
3. **Leakage?** Splits are by customer; ground truth is read only for labels and evaluation; CV groups are by transaction; a fresh seed is the final check; seed 42's test customers were never trained on.
4. **User rejects an auto-match / makes a mistake?** Reject → negative training labels. Any manual decision can be undone: the payment returns to the engine, the alias it taught is withdrawn, the training label is revoked.
5. **A customer overpays?** The surplus is kept as an unapplied credit, never applied automatically; a person can put it against another invoice of the same customer.
6. **The same bank row twice?** Detected (same amount and reference, or identical date, payer, channel and narration), classified and never reconciled.
7. **1M transactions a month?** Process incrementally by date, index outstanding invoices by customer and amount, batch scoring, run reconciliation as a queued job, shard by business. Resolution rounds only touch the small waiting set.
8. **Why a separate Python service?** The ML ecosystem and retraining live in Python; the engine depends on a tiny `Scorer` interface and falls back to rules if the service is down.
9. **How do you know it works on real data?** We don't yet. Shadow mode, compare with what accountants did, tune for precision first.

## Demo script (5 minutes)
Upload the three sample files → Run reconciliation → Dashboard → Exceptions: open a review item, show the top suggestions with scores, approve one → Decisions: undo it and show it return → Credits: apply part of an overpayment surplus → Invoices: a pay-QR with the invoice number in the UPI note → Reports: the tier comparison, then the noisy-data table and "review items with right top suggestion" → ML models panel: retrain, point out "promoted only if better".

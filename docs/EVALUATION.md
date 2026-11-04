# Evaluation: what was measured, what worked, what did not

All numbers are from synthetic data with ground truth. The model is trained on seeds that exclude seed 42's **test customers** and seed 7 entirely; "held-out" and "unseen" below refer to those. Raw outputs are in `docs/results/`, the machine-readable summary is `docs/evaluation.json`, and `scripts/evaluate.sh` regenerates both.

## Headline

| Data | Precision | Recall | Auto-matched | Final invoice status correct |
|---|---|---|---|---|
| Clean, held-out customers | 100% | 99.6% | 99.6% | 99.5% |
| Clean, unseen seed | 100% | 99.0% | 99.0% | 98.8% |
| Noisy, held-out customers | 100% | 96.5% | 96.3% | 95.5% |
| Noisy, unseen seed | 99.9% (1 wrong of 1,168) | 94.1% | 93.9% | 92.9% |
| Harsher noise (2x), unseen seed | 99.9% (1 wrong of 953) | 76.8% | 75.8% | 71.7% |

The noise: typos in payer names, invoice numbers removed from narrations, payment dates shifted 1-6 days, a flat ₹10-50 bank charge deducted from ~30% of clean payments, 12 pairs of customers with identical amounts, 15 duplicate bank rows (all doubled at 2x).

## Before and after this version (same noisy data)

| | Before | After |
|---|---|---|
| Noisy, held-out customers: recall | 87.8% | **96.5%** |
| Noisy, unseen seed: recall | 86.7% | **94.1%** |
| Harsher noise, unseen seed: recall | 73.2% | **76.8%** |
| Fee-deducted payments recovered (unseen seed, 149 cases) | 25% | **71%** |
| Short-pay recovered (30 cases) | 17% | **47%** |
| Overpayment recovered (20 cases) | 15% | **50%** |
| Noisy held-out: final invoice status correct | 84.5% | **95.5%** |

("Before" is the model and engine from the previous release, re-measured with the same datasets: `docs/results/before_*.txt`.)

Where the gain came from, step by step on the unseen noisy seed, same model first:
1. **Resolution rounds alone** (no retraining): recall 86.7% → 88.1%.
2. **Training on realistic states**: 88.1% → 94.1%. The old training rows were exported while the rule scorer drove the run, which resolves almost nothing, so invoices stayed open and candidate pools were far larger than at run time. The model had learned from a distribution it never sees in production.

## What did not work (and what it taught)

**1. Competition features: no gain.** The hypothesis: the true invoice for a short-paid payment is the one with the closest amount, and no other payment fits it better. I added four features (rank of the amount difference, margin to the runner-up, count of closer and equal payments) and retrained. Recall was flat (87.5% vs 87.8% held-out; 86.9% vs 86.7% unseen) and, in the harsh run, precision slipped from 1 wrong auto-match to 2. Looking at the data showed why: for fee-deducted payments the true invoice has an average rank in the high 50s by amount closeness, because the deducted fee (₹10-50) is as large as the natural spread of invoice amounts within a price band (₹0-100). Amount closeness carries almost no signal there. The features were removed.

**2. The model's scores are honest, not under-confident.** For short-pay-style candidates the predicted probability matches the observed rate in every band (0.6-0.8 band: predicted 0.71, actual 0.75; ≥0.9 band: predicted 0.99, actual 0.98). The 0.6-0.9 band is right about 89-90% of the time, which is why those payments belong in review and not auto. Lowering the threshold would trade correctness for recall.

**3. Raising the auto threshold is a poor trade.** On the noisy unseen seed: 0.90 → recall 94.1%; 0.95 → 90.2%; 0.97 → 86.8%; 0.99 → 82.8%, while still leaving one wrong match until 0.99 and not removing the error in the harsh run at any threshold.

**4. The strict identity policy** (never auto-match an unidentified payer without a quoted invoice number) costs about 10 points of recall (94.1% → 84.2% noisy unseen; 76.8% → 66.7% harsh) and did not reliably help (on the noisy test split it left one wrong match where the default had none). It is available (`--strict true` / `Config.requireIdentityForAuto`) and off by default.

## The wrong auto-matches, explained

Across all runs there were two kinds:
- **An amount coincidence.** A payment from an unidentified UPI handle matched an invoice to the paisa (confidence 0.999, unique exact amount) but was really a fee-deducted payment for a different invoice whose deducted amount happened to equal the first invoice. Amount-only matching always carries this coincidence risk; the strict policy is the answer if the business cannot accept it.
- **Inherent ambiguity.** A fee-deducted payment from a name shared by five customers: the wrong invoice scored 0.96, the right one 0.55. Consistent with the model's calibration (about 2.5% error in the ≥0.9 band for this class).

A third apparent error in one run was an evaluation artifact: two identical bank rows, and the engine applied the money once to the right invoice but attached it to the other row. Which of two identical rows is "the duplicate" is arbitrary, so the evaluator now accepts either.

## The review queue is cheap

Of payments sent to review, the top suggestion is already correct for 7 of 8 (noisy held-out), 51 of 58 (88%, noisy unseen) and 193 of 215 (90%, harsh). That is the realistic effort measure: most review items need one click.

## The corrections curve

With the rule scorer (where the queue is large enough to see an effect), a person correcting items one by one changes the total work: 0 corrections → 169 items; 50 corrections → 138 items remaining plus corrections made (-18%); 100 → 142. The improvement comes from the resolution rounds: each correction closes an invoice, which lets other payments resolve. (An earlier version of the engine showed a flat curve, about 163 throughout, because corrections only fixed themselves.)

## Limits

- **Synthetic data.** Amounts are exact to the paisa; payment lag and instalment fractions follow fixed patterns; fees are a flat ₹10-50. Real statements differ, so expect lower recall and re-tune thresholds on real data. Run it in shadow mode on a month of real statements first.
- **Small scenario counts** on the held-out split (6 short-pay, 5 overpay, 27 fee-deducted). Quote the unseen-seed figures (30, 20, 149) for those.
- **Run-to-run variation.** Retraining on the same data changes recall by a few tenths of a point; the 87.5 / 87.8 comparison in item 1 is within that.
- **Duplicate detection** keys on bank reference and amount; a bank that reissues a reference for different payments would need a rule change.
- Credits can only be applied within the same customer.

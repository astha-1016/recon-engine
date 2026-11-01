# Noisy-data upgrade: make the evaluation harder to attack

The clean synthetic data has amounts exact to the paisa, no typos, and fixed payment lags, so 97% on it says little about real statements. This upgrade adds realistic noise **with correct ground truth** and gives you scripts to measure before and after.

## What the noise is
| Noise | Rate | What it tests |
|---|---|---|
| Name typos (dropped / swapped / doubled letter) | ~25% of plain-name payers | Does identity resolution survive a misspelt payer? |
| Invoice number removed from the narration | ~50% of those that had one | Can matching work without Tier 1? |
| Payment-lag jitter (+1 to 6 days) | ~50% of single payments | Are we leaning on the fixed 10-day lag? |
| Bank charge deducted (flat Rs 10-50) | ~30% of clean full payments (≈135) | Short-pay handling at scale (scenario `fee_deducted`) |
| Amount collisions | 12 pairs of customers share an amount | Can amount alone identify the payer? Ambiguity must go to review, not be guessed |

`--level 0` gives exactly the clean data; `--level 2` doubles the noise.

## Steps (Git Bash, in the repository root, app running)
1. **Copy these files into your repo**, keeping the folders (`data-generator/`, `scripts/`, `docs/`). If asked, overwrite `scripts/build_training_set.py`.
2. **Apply the two small edits:** `python scripts/apply_noisy_upgrade.py`
   (publishes the ML service on port 8000 and teaches the evaluator that a fee-deducted payment should end PAID).
3. **Restart so the new port opens:** `docker compose up -d`
4. **Test the generator:** `cd data-generator && python -m unittest test_generator_v3 && cd ..` → expect `Ran 10 tests ... OK`
5. **BEFORE numbers** (current model, trained only on clean data):
   `bash scripts/evaluate_noisy.sh | tee build/noisy_before.txt`
6. **Retrain with noisy data too:** `bash scripts/train_noisy.sh`
   Read the `promoted` and `reason` fields in the output. The model is replaced only if it is better on the validation holdout.
7. **AFTER numbers:** `bash scripts/evaluate_noisy.sh | tee build/noisy_after.txt`
8. Commit: `git add -A && git commit -m "Noisy-data evaluation" && git push`

## How to report it honestly
Show three tables, not one:
1. **Clean data** (the number you already have).
2. **Noisy data, model trained on clean only**: this shows how brittle it was.
3. **Noisy data, model retrained with noise**: this shows what training on messy data buys.

Report precision first. If precision drops below 100%, that's the important finding (wrong auto-matches cost more than missed ones), so look at which scenario caused it before looking at recall. Report the weakest scenario by name.

Things worth checking in the per-scenario table: `fee_deducted` (new), `full_payment` with typos, and whether the 12 collision pairs ended up in review (good) or were auto-matched to the wrong customer (bad).

The held-out test split and the seed-7 dataset were never trained on. Seeds 201-204 (noisy) are used for training; don't evaluate on them.

## Limits
* The noise is still synthetic. It makes the test harder in realistic ways, but it is not real bank data.
* Duplicate bank rows (the same payment listed twice) are not generated, because the engine has no duplicate detection yet; that is a real gap to mention as future work.
* This package was tested here only as far as the generator, its tests, the patcher and the training-set merge. The engine and ML scoring on noisy data run on your machine, so the numbers come from you.

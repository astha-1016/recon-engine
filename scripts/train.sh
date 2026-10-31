#!/usr/bin/env bash
# Rebuilds the multi-seed training set with the Java engine, then asks the running ML service to retrain.
# The new model is promoted only if it beats the active one on the same validation holdout.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${CLASSES:-/tmp/recon-classes}
mkdir -p "$OUT"
javac --release 17 -d "$OUT" engine/src/main/java/com/reconcile/engine/*.java
python scripts/build_training_set.py --classes "$OUT"
curl -s -X POST "${ML_URL:-http://localhost:8000}/train" -H 'Content-Type: application/json' -d '{"algorithm":"gb","use_feedback":true}' | python -m json.tool

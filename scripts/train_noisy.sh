#!/usr/bin/env bash
# Rebuilds the training set with clean AND noisy datasets, then retrains.
# The service promotes the new model only if it scores better on the same validation holdout.
set -euo pipefail
cd "$(dirname "$0")/.."
if python --version >/dev/null 2>&1; then PY=python; else PY=python3; fi
ML=${ML_URL:-http://localhost:8000}

mkdir -p build/classes
javac --release 17 -d build/classes engine/src/main/java/com/reconcile/engine/*.java
$PY scripts/build_training_set.py --classes build/classes --noisy-seeds 201 202 203 204
echo "Retraining (can take a few minutes)..."
curl -s -X POST "$ML/train" -H 'Content-Type: application/json' -d '{"algorithm":"gb","use_feedback":true}' | $PY -m json.tool
echo "Now run: bash scripts/evaluate_noisy.sh"

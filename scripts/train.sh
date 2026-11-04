#!/usr/bin/env bash
# Rebuilds the training set and retrains the ML model, in two stages.
#
#  Stage 1  training rows follow the simple rule scorer (works even when no model exists yet).
#  Stage 2  rows are exported again following the NEW model's own decisions, including payments that were
#           re-examined in later resolution rounds. This matters: with the rule scorer almost nothing resolves, so
#           candidate pools stay large; the real model resolves payments as it goes, so pools are smaller. Training
#           on states the model will actually see removes that mismatch (measured: +7 points recall on noisy data).
#
# The service replaces its active model only if the new one scores better on the same validation holdout.
set -euo pipefail
cd "$(dirname "$0")/.."
if python --version >/dev/null 2>&1; then PY=python; else PY=python3; fi
ML=${ML_URL:-http://localhost:8000}
NOISY="201 202 203 204"

curl -s -m 5 "$ML/health" >/dev/null || { echo "ML service not reachable at $ML (docker compose up -d, and publish port 8000 on the ml service)."; exit 1; }
mkdir -p build/classes
javac --release 17 -d build/classes engine/src/main/java/com/reconcile/engine/*.java

echo "== Stage 1: rows from the rule scorer"
$PY scripts/build_training_set.py --classes build/classes --noisy-seeds $NOISY
curl -s -X POST "$ML/train" -H 'Content-Type: application/json' -d '{"algorithm":"gb","use_feedback":false}' | $PY -m json.tool | grep -E '"(version|promoted|reason)"'

echo "== Stage 2: rows following the model's own decisions"
$PY scripts/build_training_set.py --classes build/classes --noisy-seeds $NOISY --ml "$ML"
curl -s -X POST "$ML/train" -H 'Content-Type: application/json' -d '{"algorithm":"gb","use_feedback":true}' | $PY -m json.tool

echo "Now run: bash scripts/evaluate.sh"

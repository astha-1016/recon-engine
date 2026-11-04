#!/usr/bin/env bash
# Regenerates docs/evaluation.json from the ML service that is running (default http://localhost:8000).
# Measures clean data, noisy data and harsher noise, on held-out customers AND on datasets the model never saw.
set -euo pipefail
cd "$(dirname "$0")/.."
if python --version >/dev/null 2>&1; then PY=python; else PY=python3; fi
ML=${ML_URL:-http://localhost:8000}

curl -s -m 5 "$ML/health" >/dev/null || { echo "ML service not reachable at $ML"; exit 1; }
mkdir -p build/classes
javac --release 17 -d build/classes engine/src/main/java/com/reconcile/engine/*.java

$PY data-generator/generate_v2.py       --seed 42 --output build/clean42
$PY data-generator/generate_v2.py       --seed 7  --output build/clean7
$PY data-generator/generate_v3_noisy.py --seed 42 --level 1 --output build/noisy42
$PY data-generator/generate_v3_noisy.py --seed 7  --level 1 --output build/noisy7
$PY data-generator/generate_v3_noisy.py --seed 7  --level 2 --output build/harsh7

java -cp build/classes com.reconcile.engine.Cli report --data build/clean42 --ml "$ML" --split test --data2 build/clean7 \
  --extras "Noisy data, held-out test customers=build/noisy42:test;Noisy data, unseen seed=build/noisy7:all;Harsh noise (2x), unseen seed=build/harsh7:all" \
  --out docs/evaluation.json
echo; echo "== Noisy data, held-out test customers, by scenario"
java -cp build/classes com.reconcile.engine.Cli eval --data build/noisy42 --mode ml --ml "$ML" --split test

#!/usr/bin/env bash
# Measures the CURRENT ML model on clean data and on noisy data, side by side.
# Needs the ML service reachable (docker compose up -d, with the ml port published; see docs/NOISY_UPGRADE.md).
set -euo pipefail
cd "$(dirname "$0")/.."
if python --version >/dev/null 2>&1; then PY=python; else PY=python3; fi
ML=${ML_URL:-http://localhost:8000}

curl -s -m 5 "$ML/health" >/dev/null || { echo "ML service not reachable at $ML. Is 'ports: [\"8000:8000\"]' set on the ml service?"; exit 1; }
mkdir -p build/classes
javac --release 17 -d build/classes engine/src/main/java/com/reconcile/engine/*.java

$PY data-generator/generate_v2.py       --seed 42 --output build/clean42
$PY data-generator/generate_v3_noisy.py --seed 42 --level 1 --output build/noisy42
$PY data-generator/generate_v3_noisy.py --seed 7  --level 1 --output build/noisy7
$PY data-generator/generate_v3_noisy.py --seed 7  --level 2 --output build/harsh7

echo; echo "################ Same model, CLEAN data (held-out test customers)"
java -cp build/classes com.reconcile.engine.Cli compare --data build/clean42 --ml "$ML" --split test
echo; echo "################ Same model, NOISY data (held-out test customers)"
java -cp build/classes com.reconcile.engine.Cli compare --data build/noisy42 --ml "$ML" --split test
echo; echo "################ Noisy data, by scenario and channel (full pipeline)"
java -cp build/classes com.reconcile.engine.Cli eval --data build/noisy42 --mode ml --ml "$ML" --split test
echo; echo "################ Never-seen noisy seed, all customers (full pipeline)"
java -cp build/classes com.reconcile.engine.Cli eval --data build/noisy7 --mode ml --ml "$ML" --split all | head -6
echo; echo "################ Harsher noise (level 2), never-seen seed"
java -cp build/classes com.reconcile.engine.Cli eval --data build/harsh7 --mode ml --ml "$ML" --split all | head -6

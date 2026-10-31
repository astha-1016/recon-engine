#!/usr/bin/env bash
# Regenerates docs/evaluation.json from the ML service that is currently running (default http://localhost:8000).
set -euo pipefail
cd "$(dirname "$0")/.."
ML=${ML_URL:-http://localhost:8000}
OUT=${CLASSES:-/tmp/recon-classes}
mkdir -p "$OUT"
javac --release 17 -d "$OUT" engine/src/main/java/com/reconcile/engine/*.java

python data-generator/generate_v2.py --seed 42 --output data-generator/sample
python data-generator/generate_v2.py --seed 7 --output /tmp/recon-unseen   # a dataset the model never trained on

java -cp "$OUT" com.reconcile.engine.Cli report --data data-generator/sample --ml "$ML" --split test --data2 /tmp/recon-unseen --out docs/evaluation.json
java -cp "$OUT" com.reconcile.engine.Cli compare --data data-generator/sample --ml "$ML" --split test

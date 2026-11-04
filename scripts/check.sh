#!/usr/bin/env bash
# Runs every test suite that does not need Docker.
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; *) SEP=":";; esac
if python --version >/dev/null 2>&1; then PY=python; else PY=python3; fi
OUT=build/check          # a private folder: never touch build/ itself, it holds evaluation datasets
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"

echo "== 1/4 dataset generators"
(cd data-generator && $PY -m unittest test_generator_v2 test_generator_v3 2>&1 | tail -3)

echo "== 2/4 engine (compile + self-test)"
javac --release 17 -d "$OUT/main" engine/src/main/java/com/reconcile/engine/*.java
javac --release 17 -cp "$OUT/main" -d "$OUT/test" engine/src/test/java/com/reconcile/engine/EngineSelfTest.java
(cd engine && java -cp "../$OUT/main${SEP}../$OUT/test" com.reconcile.engine.EngineSelfTest)

echo "== 3/4 ML service"
(cd ml-service && $PY -m pytest -q tests 2>&1 | tail -3)

echo "== 4/4 frontend type-check + build"
(cd frontend && npm install --no-audit --no-fund >/dev/null && npm run build 2>&1 | tail -3)

echo "All checks passed. (The Spring Boot module needs Maven Central: run 'mvn verify' separately.)"

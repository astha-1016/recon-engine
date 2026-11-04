#!/usr/bin/env bash
# Backs up the whole database (invoices, payments, decisions, users, audit log) to backups/reconcile-<time>.dump
# Run from anywhere while `docker compose up` is running. Restore with scripts/restore.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
FILE="backups/reconcile-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T db pg_dump -U reconcile -Fc reconcile > "$FILE"
echo "Saved $FILE ($(du -h "$FILE" | cut -f1)). Keep a copy somewhere other than this machine."

#!/usr/bin/env bash
# Restores a backup made by scripts/backup.sh. This REPLACES everything currently in the database.
# Usage: scripts/restore.sh backups/reconcile-20260101-120000.dump
set -euo pipefail
cd "$(dirname "$0")/.."
FILE="${1:?usage: scripts/restore.sh <backup file>}"
[ -f "$FILE" ] || { echo "No such file: $FILE"; exit 1; }
read -r -p "This replaces ALL current data with $FILE. Type RESTORE to continue: " ANSWER
[ "$ANSWER" = "RESTORE" ] || { echo "Cancelled."; exit 1; }
docker compose stop backend
docker compose exec -T db pg_restore -U reconcile -d reconcile --clean --if-exists --no-owner < "$FILE"
docker compose start backend
echo "Restored from $FILE."

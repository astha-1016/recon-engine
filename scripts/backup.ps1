# Backs up the whole database (invoices, payments, decisions, users, audit log) to backups\reconcile-<time>.dump
# Windows PowerShell version of scripts/backup.sh. Run while `docker compose up` is running.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force backups | Out-Null
$name = "reconcile-$(Get-Date -Format 'yyyyMMdd-HHmmss').dump"
docker compose exec -T db pg_dump -U reconcile -Fc -f /tmp/backup.dump reconcile
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
docker compose cp db:/tmp/backup.dump "backups/$name"
if ($LASTEXITCODE -ne 0) { throw 'could not copy the backup out of the container' }
docker compose exec -T db rm -f /tmp/backup.dump
Write-Host "Saved backups/$name. Keep a copy somewhere other than this machine."

# Restores a backup made by scripts/backup.ps1. This REPLACES everything currently in the database.
# Usage: .\scripts\restore.ps1 backups\reconcile-20260101-120000.dump
param([Parameter(Mandatory = $true)][string]$File)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path $File)) { throw "No such file: $File" }
$answer = Read-Host "This replaces ALL current data with $File. Type RESTORE to continue"
if ($answer -ne 'RESTORE') { Write-Host 'Cancelled.'; exit 1 }
docker compose stop backend
docker compose cp $File db:/tmp/restore.dump
if ($LASTEXITCODE -ne 0) { throw 'could not copy the file into the container' }
docker compose exec -T db pg_restore -U reconcile -d reconcile --clean --if-exists --no-owner /tmp/restore.dump
docker compose exec -T db rm -f /tmp/restore.dump
docker compose start backend
Write-Host "Restored from $File."

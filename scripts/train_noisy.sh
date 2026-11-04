#!/usr/bin/env bash
# Kept so the old command still works: training now always includes noisy data. See scripts/train.sh.
exec bash "$(dirname "$0")/train.sh" "$@"

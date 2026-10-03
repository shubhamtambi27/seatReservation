#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="${1:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-admin-secret}"
HOT="${HOT:-400}"
USERS="${USERS:-80}"
exec python3 "$ROOT/burst.py" "$BASE_URL" --admin-token "$ADMIN_TOKEN" --hot "$HOT" --users "$USERS"

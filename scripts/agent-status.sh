#!/usr/bin/env bash
set -euo pipefail

if [ -z "${CODEX_API_TOKEN:-}" ]; then
  echo "ERROR: CODEX_API_TOKEN is required." >&2
  exit 1
fi

BASE_URL="${BASE_URL:-http://127.0.0.1:8765}"
BASE_URL="${BASE_URL%/}"

exec curl -sS \
  -H "Authorization: Bearer ${CODEX_API_TOKEN}" \
  "${BASE_URL}/api/agent/status"

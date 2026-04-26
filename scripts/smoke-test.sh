#!/usr/bin/env bash
set -euo pipefail

if [ -z "${CODEX_API_TOKEN:-}" ]; then
  echo "ERROR: CODEX_API_TOKEN is required." >&2
  exit 1
fi

BASE_URL="${BASE_URL:-http://127.0.0.1:8765}"
BASE_URL="${BASE_URL%/}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

request() {
  local method="$1"
  local path="$2"
  local auth="${3:-auth}"
  local body="${4:-}"
  local body_file="$TMP_DIR/response.json"
  local status
  local curl_args=(-sS -o "$body_file" -w "%{http_code}" -X "$method")

  if [ "$auth" = "auth" ]; then
    curl_args+=(-H "Authorization: Bearer ${CODEX_API_TOKEN}")
  fi

  if [ -n "$body" ]; then
    curl_args+=(-H "Content-Type: application/json" --data "$body")
  fi

  curl_args+=("${BASE_URL}${path}")
  status="$(curl "${curl_args[@]}")"

  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "ERROR: ${method} ${path} returned HTTP ${status}" >&2
    sed 's/^/  /' "$body_file" >&2 || true
    exit 1
  fi

  printf 'ok: %s %s\n' "$method" "$path" >&2
  cat "$body_file"
}

extract_session_id() {
  sed -n 's/.*"sessionId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}

request GET /health public >/dev/null
request GET /api/codex/status auth >/dev/null

session_body="$(
  request POST /api/sessions auth '{
    "name": "codex-api-server-smoke",
    "workingDirectory": ".",
    "description": "Smoke test session",
    "defaultTimeoutSeconds": 600
  }'
)"
session_id="$(printf '%s' "$session_body" | extract_session_id)"

if [ -z "$session_id" ]; then
  echo "ERROR: unable to read sessionId from create-session response." >&2
  exit 1
fi

request GET /api/sessions auth >/dev/null
request GET "/api/sessions/${session_id}/tree" auth >/dev/null
request GET "/api/sessions/${session_id}/files/content?path=pom.xml" auth >/dev/null
request GET "/api/sessions/${session_id}/git/status" auth >/dev/null
request GET "/api/sessions/${session_id}/git/diff" auth >/dev/null
request POST "/api/sessions/${session_id}/codex/exec" auth '{
  "prompt": "Reply exactly: codex-api-server smoke test",
  "sandbox": "read-only",
  "approvalPolicy": "never",
  "ephemeral": true,
  "timeoutSeconds": 120
}' >/dev/null
request GET "/api/sessions/${session_id}/history" auth >/dev/null

echo "Smoke test passed."

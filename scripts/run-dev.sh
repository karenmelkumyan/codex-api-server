#!/usr/bin/env bash
set -euo pipefail

if [ -z "${CODEX_API_TOKEN:-}" ]; then
  echo "ERROR: CODEX_API_TOKEN is required." >&2
  exit 1
fi

cat >&2 <<'INFO'
Starting codex-api-server with Maven exec.

Optional environment variables:
  CODEX_API_HOST
  CODEX_API_PORT
  CODEX_CLI_PATH
  CODEX_SESSIONS_FILE
  CODEX_EXEC_DEFAULT_TIMEOUT
  CODEX_EXEC_MAX_TIMEOUT
  CODEX_MAX_REQUEST_BYTES
  CODEX_ALLOW_REMOTE_BIND
INFO

exec mvn -q exec:java

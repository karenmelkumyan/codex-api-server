#!/usr/bin/env bash
set -euo pipefail

if [ -z "${CODEX_API_TOKEN:-}" ]; then
  echo "ERROR: CODEX_API_TOKEN is required." >&2
  exit 1
fi

export CODEX_AGENT_ENABLED="${CODEX_AGENT_ENABLED:-true}"
export CODEX_AGENT_BRIDGE_BASE_URL="${CODEX_AGENT_BRIDGE_BASE_URL:-https://emebridge.eagma.com}"
export CODEX_AGENT_WORKING_DIRECTORY="${CODEX_AGENT_WORKING_DIRECTORY:-$(pwd)}"
export CODEX_AGENT_STATE_FILE="${CODEX_AGENT_STATE_FILE:-./data/agent.json}"

cat >&2 <<INFO
Starting codex-api-server with connector mode.

Connector environment:
  CODEX_AGENT_ENABLED=${CODEX_AGENT_ENABLED}
  CODEX_AGENT_BRIDGE_BASE_URL=${CODEX_AGENT_BRIDGE_BASE_URL:-}
  CODEX_AGENT_STATE_FILE=${CODEX_AGENT_STATE_FILE}
  CODEX_AGENT_WORKING_DIRECTORY=${CODEX_AGENT_WORKING_DIRECTORY}
  CODEX_AGENT_SANDBOX_MODE=${CODEX_AGENT_SANDBOX_MODE:-workspace-write}
INFO

exec mvn -q exec:java

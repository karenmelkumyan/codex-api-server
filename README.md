# codex-api-server

A small Java/Maven HTTP API server for controlled local Codex integrations.

V1 is ready for local use with token-protected APIs, saved project sessions,
read-only inspection, controlled non-interactive Codex execution, and optional
outbound connector mode for `eme-codex-bridge` relay jobs.

See [SPEC.md](SPEC.md) for the repository-owned project specification.

The current implementation intentionally keeps the server local and explicit:

- Java with Maven
- JDK built-in `HttpServer`
- Jackson JSON utilities
- Environment-based configuration
- Public health check plus authenticated API routes
- Bearer authentication for protected API routes
- Codex CLI status diagnostics
- Server-side project session management
- Read-only project inspection endpoints
- Non-interactive Codex execution for saved sessions
- Default-enabled outbound connector mode for `eme-codex-bridge`
- Local agent bootstrap, state storage, and pairing-code requests
- Authenticated outbound `/agent/ws` WebSocket with hello, heartbeat, and reconnect
- Relay execution for `codex_readonly`, `codex_verify`, and `codex_change`
- No arbitrary shell execution

## Requirements

- JDK 17 or newer
- Maven 3.9 or newer
- `curl` for the smoke test script

## Quickstart

Use `.env.example` as a template for local environment values. Set
`CODEX_API_TOKEN` to a local development token, then run:

```bash
mvn -q test
mvn -q -DskipTests package
CODEX_API_TOKEN=dev-token ./scripts/run-dev.sh
```

In another terminal:

```bash
CODEX_API_TOKEN=dev-token ./scripts/smoke-test.sh
```

The connector is enabled by default and uses
`https://emebridge.eagma.com`. Set `CODEX_AGENT_ENABLED=false` only when
you want to run the local HTTP API without the outbound bridge connector.

## Build

```bash
mvn -q test
mvn -q -DskipTests package
```

The packaged build also creates downloadable distribution archives:

```text
target/codex-api-server-0.1.0-SNAPSHOT-dist.zip
target/codex-api-server-0.1.0-SNAPSHOT-dist.tar.gz
```

Each archive includes the runnable shaded JAR, `bin/` launchers, `.env.example`,
the smoke-test helper, and `INSTALL.md`. Users can extract the archive, change
to the project directory Codex should work on, and run:

```bash
/path/to/codex-api-server-0.1.0-SNAPSHOT/bin/codex-api-server
```

No configuration file is required for the default EME bridge connection. Create
`.env` from `.env.example` only when you want to set a local API token or
override defaults. The bundle stores its own state under the extracted
distribution directory, while the default Codex working directory is the
directory where the launcher is invoked.

If the configured port is already in use, the packaged launcher shows the
existing listener and asks whether to restart it. Press Enter to stop the
existing listener and start again, or type anything else to keep the current
process. For scripts, set `CODEX_API_RESTART_EXISTING=true` to restart without
an interactive prompt.

## Run For Development

```bash
CODEX_API_TOKEN=dev-token ./scripts/run-dev.sh
```

By default, the server listens on:

```text
http://127.0.0.1:8765
```

To run with the default EME bridge connector diagnostics:

```bash
CODEX_API_TOKEN=dev-token ./scripts/run-agent-dev.sh
```

For local bridge development, override `CODEX_AGENT_BRIDGE_BASE_URL`.

## Run Packaged Jar

Build the runnable jar:

```bash
mvn -q -DskipTests package
```

Run it:

```bash
java -jar target/codex-api-server-0.1.0-SNAPSHOT.jar
```

Set `CODEX_API_TOKEN` when you need protected local `/api/*` routes.

## Smoke Test

With the server running:

```bash
CODEX_API_TOKEN=dev-token ./scripts/smoke-test.sh
```

Override the target URL when needed:

```bash
BASE_URL=http://127.0.0.1:8765 CODEX_API_TOKEN=dev-token ./scripts/smoke-test.sh
```

## Minimal Curl Examples

Health check:

```bash
curl http://127.0.0.1:8765/health
```

Protected API routes require bearer token authentication:

```bash
CODEX_API_TOKEN=dev-token mvn exec:java
curl -H 'Authorization: Bearer dev-token' http://127.0.0.1:8765/api/codex/status
```

Connector diagnostics:

```bash
CODEX_API_TOKEN=dev-token ./scripts/agent-status.sh
CODEX_API_TOKEN=dev-token ./scripts/agent-pairing-code.sh
```

Create a session:

```bash
curl -X POST http://127.0.0.1:8765/api/sessions \
  -H 'Authorization: Bearer dev-token' \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "codex-api-server",
    "workingDirectory": ".",
    "description": "Java API server around local Codex CLI",
    "defaultTimeoutSeconds": 600
  }'
```

## Configuration

Configuration is read from environment variables.

| Variable | Default | Description |
| --- | --- | --- |
| `CODEX_API_HOST` | `127.0.0.1` | Host address for the HTTP server. |
| `CODEX_API_PORT` | `8765` | Port for the HTTP server. |
| `CODEX_API_RESTART_EXISTING` | `false` | Packaged launcher option. When the configured port is already in use, `true` stops the existing listener and starts a new server without prompting. |
| `CODEX_API_TOKEN` | unset | Optional bearer token for protected `/api/*` routes. When unset, protected local API routes return `AUTH_TOKEN_NOT_CONFIGURED`. |
| `CODEX_CLI_PATH` | `codex` | Path or command name for the Codex CLI status check. |
| `CODEX_SESSIONS_FILE` | `./data/sessions.json` | Session storage file path. |
| `CODEX_EXEC_DEFAULT_TIMEOUT` | `600` | Default execution timeout in seconds. |
| `CODEX_EXEC_MAX_TIMEOUT` | `1800` | Maximum execution timeout in seconds. |
| `CODEX_ALLOW_REMOTE_BIND` | `false` | Allows binding to non-local hosts when set to `true`. |
| `CODEX_MAX_REQUEST_BYTES` | `1000000` | Maximum JSON request body size for POST endpoints. |
| `CODEX_AGENT_ENABLED` | `true` | Enables the outbound local-agent connector. Set to `false` to run without bridge pairing. |
| `CODEX_AGENT_BRIDGE_BASE_URL` | `https://emebridge.eagma.com` | Bridge base URL for connector bootstrap, pairing, status, and WebSocket paths. |
| `CODEX_AGENT_STATE_FILE` | `~/.codex/eme-codex-agent/agent.json` | Local connector identity storage file. |
| `CODEX_AGENT_DISPLAY_NAME` | `Codex Local Agent` | Human-readable connector display name sent to the bridge. |
| `CODEX_AGENT_CLIENT_VERSION` | `codex-api-server/0.1.0-SNAPSHOT` | Connector client version sent to the bridge. |
| `CODEX_AGENT_WORKING_DIRECTORY` | `.` | Local repository root for relay job execution, stored as a normalized absolute path. |
| `CODEX_AGENT_AUTO_BOOTSTRAP` | `true` | Allows the connector to bootstrap a local agent identity when no state exists. |
| `CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP` | `true` | Allows first bootstrap to request and show a pairing code. |
| `CODEX_AGENT_PAIR_ON_START` | `true` | Requests and prints a fresh pairing code on each connector start. |
| `CODEX_AGENT_HEARTBEAT_INTERVAL_SECONDS` | `30` | Connector WebSocket heartbeat interval. |
| `CODEX_AGENT_RECONNECT_INITIAL_SECONDS` | `2` | Initial reconnect delay for bridge connection attempts. |
| `CODEX_AGENT_RECONNECT_MAX_SECONDS` | `60` | Maximum reconnect delay for bridge connection attempts. |
| `CODEX_AGENT_JOB_MAX_TIMEOUT_SECONDS` | `CODEX_EXEC_MAX_TIMEOUT` | Maximum relay job timeout accepted by the connector. |
| `CODEX_AGENT_SANDBOX_MODE` | `workspace-write` | Sandbox used for `codex_verify` and `codex_change` relay jobs. Set to `danger-full-access` only when you trust the task and want Codex CLI to run without filesystem sandboxing. |

Remote binding is disabled by default. If `CODEX_API_HOST` is set to a non-local
address, `CODEX_ALLOW_REMOTE_BIND=true` must also be set.

Connector mode is enabled by default and is outbound-only. It uses
`https://emebridge.eagma.com` unless `CODEX_AGENT_BRIDGE_BASE_URL` is
overridden. The HTTP server can still start if bridge bootstrap or connection
fails; the connector reports the startup error through `/api/agent/status` when
a local API token is configured. The local agent state file stores
`agentSecret`; it is written with owner-only permissions where the filesystem
supports POSIX permissions.
The connector capability payload reports Codex CLI availability and supported
relay tool modes without sending the full working directory path.
When active, connector startup loads existing local identity state, bootstraps
only when state is missing and auto-bootstrap is enabled, and prints pairing
code details without printing the stored agent secret. By default, startup also
prints a fresh pairing code when an existing identity is loaded, so restarting
the launcher is enough to get a new code.
Once state is ready, the connector starts the outbound WebSocket in the
background, sends `agent.hello`, keeps heartbeat diagnostics, and currently
accepts relay jobs for `codex_readonly`, `codex_verify`, and `codex_change`.
Relay execution is single-job-at-a-time, uses `approvalPolicy=never` and
ephemeral Codex runs, sends bounded `job.progress` start/heartbeat messages
while Codex is running, and returns safe result fields. By default, mutable
relay jobs use the Codex CLI `workspace-write` sandbox. Set
`CODEX_AGENT_SANDBOX_MODE=danger-full-access` to run mutable relay jobs with
full filesystem access; `codex_readonly` remains `read-only`.
This repository implements connector-side relay support. EME Chat now provides
the server-side connector binding endpoints and browser-facing connector
state/claim flow; the full end-to-end product flow across deployed EME Chat,
`eme-codex-bridge`, and a local connector still needs separate validation.

## Connector Relay Smoke Flow

This manual flow validates the bridge plus connector path without EME Chat UI.
It assumes `../eme-codex-bridge` is checked out next to this repository, MySQL
is available for the bridge, and EME backend credentials/session values are
configured for bridge registration. The examples use `jq` for convenience; if
it is unavailable, copy the printed JSON values manually.

1. Start the bridge and its database:

```bash
cd ../eme-codex-bridge
docker compose up -d mysql

export BRIDGE_ADMIN_TOKEN=dev-bridge-token
export EME_API_BASE_URL=https://eme-api.example.test
export EME_SECRET_KEY=eme-registration-secret
export BRIDGE_PUBLIC_BASE_URL=http://127.0.0.1:8787
export BRIDGE_AGENT_BOOTSTRAP_ENABLED=true
export BRIDGE_AGENT_RELAY_ENABLED=true
./scripts/run-dev.sh
```

2. In this repository, start `codex-api-server` with connector mode enabled:

```bash
cd ../codex-api-server
export CODEX_API_TOKEN=dev-token
export CODEX_AGENT_ENABLED=true
export CODEX_AGENT_BRIDGE_BASE_URL=http://127.0.0.1:8787
export CODEX_AGENT_WORKING_DIRECTORY="$(pwd)"
export CODEX_AGENT_STATE_FILE="$HOME/.codex/eme-codex-agent/dev-agent.json"
./scripts/run-agent-dev.sh
```

The connector bootstraps `agentId + agentSecret` on first start, stores them in
`CODEX_AGENT_STATE_FILE`, opens the outbound WebSocket, and prints a pairing
code when first-bootstrap pairing is enabled.

3. Check local connector status and request a fresh pairing code:

```bash
export CODEX_API_TOKEN=dev-token
AGENT_STATUS_JSON="$(./scripts/agent-status.sh)"
echo "$AGENT_STATUS_JSON" | jq .

PAIRING_JSON="$(./scripts/agent-pairing-code.sh)"
echo "$PAIRING_JSON" | jq .

PAIRING_CODE="$(printf '%s' "$PAIRING_JSON" | jq -r '.pairingCode')"
AGENT_ID="$(printf '%s' "$AGENT_STATUS_JSON" | jq -r '.agentId')"
```

`/api/agent/status` should show `active: true` and eventually
`connected: true`. It never returns `agentSecret`.

4. Claim the pairing code through the bridge. This substitutes for the EME Chat
browser claim flow when manually validating bridge and connector behavior:

```bash
export BRIDGE_BASE_URL=http://127.0.0.1:8787
export BRIDGE_ADMIN_TOKEN=dev-bridge-token
export EXTERNAL_OWNER_ID=manual-owner-1

CLAIM_JSON="$(curl -sS -X POST "${BRIDGE_BASE_URL%/}/api/agent-pairings/claim" \
  -H "Authorization: Bearer ${BRIDGE_ADMIN_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"pairingCode\": \"${PAIRING_CODE}\",
    \"externalOwnerId\": \"${EXTERNAL_OWNER_ID}\",
    \"expectedAgentType\": \"codex\"
  }")"
echo "$CLAIM_JSON" | jq .

AGENT_ID="$(printf '%s' "$CLAIM_JSON" | jq -r '.agentId')"
```

5. Create an `AGENT_RELAY` bridge registration for an EME session:

```bash
export EME_SESSION_ID=123
export EME_SESSION_TOKEN=session-token-from-eme

REGISTRATION_JSON="$(curl -sS -X POST "${BRIDGE_BASE_URL%/}/api/registrations" \
  -H "Authorization: Bearer ${BRIDGE_ADMIN_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{
    \"emeSessionId\": ${EME_SESSION_ID},
    \"emeSessionToken\": \"${EME_SESSION_TOKEN}\",
    \"name\": \"local-codex-agent\",
    \"routingMode\": \"AGENT_RELAY\",
    \"agentId\": \"${AGENT_ID}\",
    \"externalOwnerId\": \"${EXTERNAL_OWNER_ID}\",
    \"verifyAccess\": true
  }")"
echo "$REGISTRATION_JSON" | jq .

BRIDGE_KEY="$(printf '%s' "$REGISTRATION_JSON" | jq -r '.bridgeKey')"
```

For local bridge-only experiments you can set `"verifyAccess": false`, but a
real registration still needs bridge EME configuration when tool registration is
part of the flow.

6. Submit a relay tool call through the bridge and read the result through
`codex_read_log`:

```bash
SUBMIT_JSON="$(curl -sS -X POST "${BRIDGE_BASE_URL%/}/tools/${BRIDGE_KEY}/codex_readonly" \
  -H 'Content-Type: application/json' \
  -d '{
    "message": "Inspect this repository and summarize the connector mode implementation.",
    "timeoutSeconds": 600
  }')"
echo "$SUBMIT_JSON" | jq .

JOB_ID="$(printf '%s' "$SUBMIT_JSON" | jq -r '.jobId')"

curl -sS -X POST "${BRIDGE_BASE_URL%/}/tools/${BRIDGE_KEY}/codex_read_log" \
  -H 'Content-Type: application/json' \
  -d "{
    \"limit\": 20,
    \"includeCompleted\": true,
    \"includeRunning\": true,
    \"includeFailed\": true
  }" | jq --arg job_id "$JOB_ID" '.jobs[] | select(.jobId == $job_id)'
```

The bridge dispatches `job.request` over the connector WebSocket. The connector
sends `job.accepted`, sends `job.progress` when execution starts and as a
periodic heartbeat, executes Codex locally, and sends `job.result`; the bridge
stores safe progress events and the safe response for `codex_read_log`.

## Relay Execution Internals

Relay jobs do not call the local HTTP API. The connector shares the same
`ProcessRunner` and `SessionService` that `Router` uses, then calls
`CodexService.execute(...)` directly with a generated `CodexExecRequest`.

For relay execution, the connector creates or reuses an internal saved session
with session id `agent_connector` and working directory
`CODEX_AGENT_WORKING_DIRECTORY`. That session is stored in `CODEX_SESSIONS_FILE`
and may appear in `/api/sessions`. Because execution goes through
`CodexService`, relay jobs write the same metadata-only execution history as
normal `POST /api/sessions/{sessionId}/codex/exec` calls: prompt text is
omitted, stdout/stderr previews are bounded, and the session `lastUsedAt` value
is updated. Progress heartbeats are generic and do not stream stdout or stderr.

If the WebSocket disconnects while Codex is still running, V1 lets the local
Codex process continue. When the process completes, the connector attempts to
send `job.result` on the same WebSocket instance that delivered the original
`job.request`. It does not queue that result for replay on a later reconnect. If
that socket is already closed, the result is effectively dropped from the
connector side; the bridge remains authoritative for marking disconnected or
stale relay jobs failed or timed out. Local execution history may still be
written even when the bridge never receives the final result.

## Current Routes

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/health` | Returns a basic JSON health response. |
| `GET` | `/api/codex/status` | Checks whether the configured Codex CLI can run `<CODEX_CLI_PATH> --version`. |
| `GET` | `/api/agent/status` | Returns safe local connector diagnostics. |
| `POST` | `/api/agent/pairing-code` | Requests a bridge pairing code for an existing local agent state. |
| `POST` | `/api/sessions` | Creates a saved project session. |
| `GET` | `/api/sessions` | Lists saved project sessions. |
| `GET` | `/api/sessions/{sessionId}` | Reads one saved project session. |
| `DELETE` | `/api/sessions/{sessionId}` | Deletes the session record without deleting project files. |
| `GET` | `/api/sessions/{sessionId}/tree` | Lists project files below a session path. |
| `GET` | `/api/sessions/{sessionId}/files/content?path=...` | Reads text file content below the session root. |
| `GET` | `/api/sessions/{sessionId}/git/status` | Runs `git --no-pager status --short --branch` in the session root. |
| `GET` | `/api/sessions/{sessionId}/git/diff` | Runs `git --no-pager diff --no-ext-diff` or the staged variant in the session root. |
| `POST` | `/api/sessions/{sessionId}/codex/exec` | Runs one non-interactive Codex CLI task in the session root. |
| `GET` | `/api/sessions/{sessionId}/history` | Lists execution metadata for the session. |

All `/api/*` paths require `Authorization: Bearer <token>` when
`CODEX_API_TOKEN` is configured. If `CODEX_API_TOKEN` is missing, protected API
routes return `AUTH_TOKEN_NOT_CONFIGURED`.

`/api/codex/status` is diagnostic. If the Codex CLI is unavailable or exits with
a non-zero status, the endpoint still returns `200` with `available: false`.

Unknown paths return a structured `404` JSON response.

## Sessions

Sessions are stored as JSON at `CODEX_SESSIONS_FILE`. The default location is:

```text
./data/sessions.json
```

A session contains:

- `sessionId`
- `name`
- `workingDirectory`
- `description`
- `createdAt`
- `lastUsedAt`
- `defaultTimeoutSeconds`

`workingDirectory` may be relative, such as `.`, but it is resolved with
`Path.of(input).toAbsolutePath().normalize()` before validation. The normalized
absolute path is stored and returned as the session root for future operations.
The resolved path must exist and must be a directory. Deleting a session only
removes the saved session record; it does not delete files from the project.

## Read-Only Inspection

Inspection endpoints are authenticated and scoped to the saved session root.
Requested paths are resolved against the session root and must remain inside it.
Path traversal and symlink escapes are rejected.

Tree query parameters:

- `path`: optional, defaults to `.`
- `maxDepth`: optional, defaults to `4`
- `limit`: optional, defaults to `500`

File content query parameters:

- `path`: required
- `maxBytes`: optional, defaults to `200000`
- `encoding`: optional, defaults to `UTF-8`

Git diff query parameters:

- `staged`: optional boolean, defaults to `false`
- `maxBytes`: optional, defaults to `300000`

Process output capture is bounded in memory. Git diff passes the endpoint
`maxBytes` value as the stdout capture limit and reports `truncated: true` when
stdout is capped.

## Codex Execution

`POST /api/sessions/{sessionId}/codex/exec` runs the configured Codex CLI with
`ProcessBuilder` directly. It does not use a shell and does not expose arbitrary
command execution.

The request body is JSON:

```json
{
  "prompt": "Inspect the project and summarize it.",
  "timeoutSeconds": 600,
  "model": null,
  "profile": null,
  "sandbox": "read-only",
  "approvalPolicy": "never",
  "ephemeral": true,
  "skipGitRepoCheck": false
}
```

`prompt` is required and is passed to Codex through stdin. The command shape is:

```text
<CODEX_CLI_PATH> exec --cd <session-root> --color never [options] --output-last-message <temp-file> -
```

Allowed sandbox values are `read-only` and `workspace-write`.
The unrestricted full-access sandbox mode is not allowed. Allowed approval policies are `untrusted`,
`on-request`, and `never`. The approval policy is passed through Codex config
override syntax rather than a shell:

```text
-c approval_policy="<value>"
```

The server does not use `--ask-for-approval` because the installed Codex CLI
version tested here does not support that flag for `codex exec`.

The execution response includes stdout/stderr and command metadata, but it does
not echo the full prompt. Session history stores metadata, a prompt-omitted
placeholder, and short stdout/stderr previews only, not full stdout/stderr.

To inspect local CLI support manually:

```bash
codex exec --help
```

Codex CLI flags may vary by version. This server intentionally supports only the
non-interactive `codex exec` subset documented here.

## Error Responses

Errors use a consistent JSON shape:

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable message",
    "details": {}
  }
}
```

Known routes return `METHOD_NOT_ALLOWED` for unsupported HTTP methods. Oversized
JSON request bodies return `REQUEST_TOO_LARGE`.

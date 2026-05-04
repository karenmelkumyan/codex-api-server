# codex-api-server Specification

## Purpose

`codex-api-server` is a small local HTTP API server intended to provide a controlled JSON interface around Codex-oriented workflows. The service is designed to run from the current repository root, keep configuration explicit, and grow in small milestones. It also provides optional outbound connector-side support for `eme-codex-bridge` local-agent relay mode.

The project uses Java, Maven, the JDK built-in `HttpServer`, and Jackson for JSON. It does not use Spring Boot.

## V1 Endpoint Plan

The V1 API should expose a minimal set of endpoints:

- `GET /health`: return basic service health.
- `GET /api/codex/status`: report whether the configured Codex CLI can run `<CODEX_CLI_PATH> --version`.
- `GET /api/agent/status`: return safe local connector diagnostics.
- `POST /api/agent/pairing-code`: request a pairing code for an existing local connector identity.
- Session endpoints: create, list, read, and delete saved project session records.
- Read-only inspection endpoints: expose safe project tree, text file content, and git status/diff output.
- Codex execution endpoint: accept a scoped request that invokes the configured Codex CLI under strict validation and timeout limits.

`GET /health` is public and returns only simple public server health information.
Protected `/api/*` routes require bearer token authentication. `GET
/api/codex/status` is implemented as a diagnostic route. Project session
management endpoints and read-only project inspection endpoints are implemented.
Non-interactive Codex execution is implemented for saved sessions. Unknown paths
return a structured JSON `404`.

Optional connector mode is also implemented for the local side of
`eme-codex-bridge` relay registration. It is disabled by default, connects
outward to the bridge, stores local agent identity state, requests pairing
codes, maintains an authenticated WebSocket, and executes relay jobs for
`codex_readonly`, `codex_verify`, and `codex_change`.

## V1 Implemented Status

V1 currently implements:

- `GET /health`
- `GET /api/codex/status`
- `GET /api/agent/status`
- `POST /api/agent/pairing-code`
- `POST /api/sessions`
- `GET /api/sessions`
- `GET /api/sessions/{sessionId}`
- `DELETE /api/sessions/{sessionId}`
- `GET /api/sessions/{sessionId}/tree`
- `GET /api/sessions/{sessionId}/files/content?path=...`
- `GET /api/sessions/{sessionId}/git/status`
- `GET /api/sessions/{sessionId}/git/diff`
- `POST /api/sessions/{sessionId}/codex/exec`
- `GET /api/sessions/{sessionId}/history`

Streaming responses and async job management are deferred beyond V1.

## Session Concept

A session represents one saved project context managed by the API server. Session records include:

- `sessionId`
- `name`
- `workingDirectory`
- `description`
- `createdAt`
- `lastUsedAt`
- `defaultTimeoutSeconds`

Session data should be persisted under the configured sessions file path. The default is relative to the current repository root:

```text
./data/sessions.json
```

Session handling should avoid embedding machine-specific absolute paths unless they are explicitly supplied by a caller and are safe to retain.

Session endpoints:

- `POST /api/sessions`: create a saved project session.
- `GET /api/sessions`: list saved sessions.
- `GET /api/sessions/{sessionId}`: read a saved session.
- `DELETE /api/sessions/{sessionId}`: delete only the saved session record.

Deleting a session must not delete project files.

Session validation rules:

- `name` is required and non-blank.
- `workingDirectory` is required and non-blank.
- Relative `workingDirectory` values are accepted.
- `workingDirectory` is resolved with `Path.of(input).toAbsolutePath().normalize()`.
- The normalized absolute working directory is stored and returned as the session root.
- `workingDirectory` must exist.
- `workingDirectory` must be a directory.
- `defaultTimeoutSeconds` is optional.
- `defaultTimeoutSeconds`, when provided, must be positive.
- `defaultTimeoutSeconds`, when provided, must not exceed `CODEX_EXEC_MAX_TIMEOUT`.

## Read-Only Endpoints

Read-only endpoints should provide safe introspection without mutating project files. Git inspection uses read-only git commands through `ProcessRunner`.

Planned read-only behavior includes:

- Checking configured Codex CLI availability.
- Listing known sessions.
- Reading session metadata.
- Reading a bounded project tree.
- Reading bounded text file content.
- Reading git status and diff output.
- Returning status information needed by clients.

Read-only responses must not reveal secrets such as API tokens.

Read-only inspection endpoints:

- `GET /api/sessions/{sessionId}/tree`
- `GET /api/sessions/{sessionId}/files/content?path=...`
- `GET /api/sessions/{sessionId}/git/status`
- `GET /api/sessions/{sessionId}/git/diff`

Path security rules:

- Session roots are resolved to real paths before file inspection.
- Requested paths are resolved against the session root and normalized.
- Existing requested paths are checked with real path containment.
- Requested paths must remain inside the real session root.
- Symlinks must not allow access outside the session root.

Tree behavior:

- Default `path` is `.`.
- Default `maxDepth` is `4`.
- Default `limit` is `500`.
- Skip heavy/common directories by default, including `.git`, `target`, `build`, `node_modules`, `.idea`, and `.gradle`.
- Do not follow symlink directories.
- Return relative paths from the session root.

File content behavior:

- `path` is required.
- Default `maxBytes` is `200000`.
- Default `encoding` is `UTF-8`.
- Files must exist and be regular files.
- Obvious binary files are rejected with `BINARY_FILE_NOT_SUPPORTED`.
- Content is truncated when it exceeds `maxBytes`.

Git inspection behavior:

- `GET /api/sessions/{sessionId}/git/status` runs `git --no-pager status --short --branch`.
- `GET /api/sessions/{sessionId}/git/diff` runs `git --no-pager diff --no-ext-diff` by default.
- `GET /api/sessions/{sessionId}/git/diff?staged=true` runs `git --no-pager diff --no-ext-diff --staged`.
- Git commands use `ProcessRunner` directly without a shell.
- Git commands run with the session working directory.
- Process output capture is bounded by default to `1000000` bytes each for stdout and stderr.
- Git diff passes the endpoint `maxBytes` value as its stdout capture limit.

## Codex Execution Endpoint

The Codex execution endpoint runs only the configured Codex CLI path and must not provide arbitrary shell execution.

The endpoint should:

- Accept structured JSON input.
- Validate requested session and working directory constraints.
- Apply default and maximum timeout settings.
- Capture structured process output.
- Persist session updates where appropriate.
- Return clear JSON errors for validation failures, timeouts, and process failures.
- Pass the prompt to Codex through stdin.
- Avoid dangerous bypass/yolo flags.

Codex execution route:

- `POST /api/sessions/{sessionId}/codex/exec`

Command shape:

```text
<CODEX_CLI_PATH> exec --cd <session-root> --color never [options] --output-last-message <temp-file> -
```

Allowed request fields:

- `prompt`: required non-blank text, passed on stdin.
- `timeoutSeconds`: optional positive integer not exceeding `CODEX_EXEC_MAX_TIMEOUT`.
- `model`: optional non-blank model name.
- `profile`: optional non-blank profile name.
- `sandbox`: optional; allowed values are `read-only` and `workspace-write`.
- `approvalPolicy`: optional; allowed values are `untrusted`, `on-request`, and `never`.
- `ephemeral`: optional boolean.
- `skipGitRepoCheck`: optional boolean.

The unrestricted full-access sandbox mode is not allowed in V1. Execution uses `ProcessBuilder`
directly without a shell. Approval policy is passed as a Codex config override.
Stderr may contain progress logs and is not treated as failure by itself.

Approval policy mapping:

- `untrusted` maps to `-c approval_policy="untrusted"`.
- `on-request` maps to `-c approval_policy="on-request"`.
- `never` maps to `-c approval_policy="never"`.

The server does not use `--ask-for-approval` because the installed Codex CLI
version tested for this milestone does not support that flag for `codex exec`.
Local CLI support can be checked with:

```bash
codex exec --help
```

Codex CLI flags may vary by version. This server intentionally supports only the
tested V1-safe subset documented here.

Execution history:

- `GET /api/sessions/{sessionId}/history`
- Stores metadata only.
- Includes execution ID, session ID, timestamps, exit code, duration, timeout flag, a prompt-omitted placeholder, and short stdout/stderr previews.
- Preview limits are 500 characters for stdout/stderr, with a truncation marker when capped.
- Does not persist full stdout/stderr by default.
- Does not persist raw prompt text.
- Updates session `lastUsedAt` after execution attempts.

## Security Rules

Security rules for this project:

- Do not expose arbitrary shell execution.
- Only invoke the configured Codex CLI for Codex execution.
- Pass prompts through stdin instead of command arguments.
- Bind to `127.0.0.1` by default.
- Require `CODEX_ALLOW_REMOTE_BIND=true` before binding to non-local hosts.
- Keep `GET /health` public.
- Require bearer token authentication for protected `/api/*` routes.
- Return `AUTH_TOKEN_NOT_CONFIGURED` if a protected API route is called without `CODEX_API_TOKEN` configured.
- Never include token values in health, config, log, or error responses.
- Never include connector `agentSecret` values in logs, API responses, `toString`, or exception messages.
- Validate all numeric configuration values.
- Run diagnostic processes with `ProcessBuilder` directly, not through a shell.
- Enforce execution timeouts when command execution is introduced.
- Keep file access scoped and explicit.
- Prefer structured JSON input over free-form command strings.

## Configuration Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `CODEX_API_HOST` | `127.0.0.1` | Host address for the HTTP server. |
| `CODEX_API_PORT` | `8765` | Port for the HTTP server. |
| `CODEX_API_RESTART_EXISTING` | `false` | Packaged launcher option. When the configured port is already in use, `true` stops the existing listener and starts a new server without prompting. |
| `CODEX_API_TOKEN` | unset | Optional bearer token for protected `/api/*` routes. When unset, protected local API routes return `AUTH_TOKEN_NOT_CONFIGURED`. |
| `CODEX_CLI_PATH` | `codex` | Path or command name for the Codex CLI. |
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

## Connector Mode Foundation

Connector mode is enabled by default and is intended to connect outward to
`eme-codex-bridge` without adding public inbound ports. The default bridge base
URL is `https://emebridge.eagma.com`. Set `CODEX_AGENT_ENABLED=false` to run
without the connector, or override `CODEX_AGENT_BRIDGE_BASE_URL` for local or
staging bridge development.

The connector identity state file contains:

- `agentId`
- `agentSecret`
- `agentType`
- `bridgeBaseUrl`
- `relayWebSocketUrl`
- `displayName`
- `createdAt`
- `lastConnectedAt`

The state file must be written atomically. Parent directories are created when
needed, and owner-only POSIX permissions are applied to the state directory and
file where the filesystem supports them. If the state file exists but is invalid,
startup or state-store initialization must fail clearly and must not overwrite
the file. The user can delete `CODEX_AGENT_STATE_FILE` to reset the local
connector identity.

Connector startup runs after the local HTTP server starts and must not break the
existing local HTTP API when connector work fails. Startup is a no-op when
`CODEX_AGENT_ENABLED=false`, and reports inactive if no bridge base URL is
available. If valid state already exists, the connector loads it and must not
bootstrap again. If no state exists and
`CODEX_AGENT_AUTO_BOOTSTRAP=true`, it bootstraps through the bridge, stores the
returned identity locally, and can request one pairing code. If
`CODEX_AGENT_AUTO_BOOTSTRAP=false`, missing state leaves the connector inactive.
If `CODEX_AGENT_AUTO_PAIR_ON_FIRST_BOOTSTRAP=true` and
`CODEX_AGENT_PAIR_ON_START=true`, startup requests only one pairing code during
first bootstrap. When an existing identity is loaded and
`CODEX_AGENT_PAIR_ON_START=true`, startup requests and prints a fresh pairing code. If
saved state belongs to a different bridge base URL than the configured
`CODEX_AGENT_BRIDGE_BASE_URL`, startup reports an inactive/error state with
reset guidance and does not overwrite the state file.

Connector bootstrap capabilities are safe to send to the bridge and must not
include the full working directory path. The capabilities object includes:

- `codexCliAvailable`
- `codexCliVersion` when the Codex CLI status command succeeds
- `supportsReadonly`
- `supportsVerify`
- `supportsChange`
- `workingDirectoryConfigured`

The bridge HTTP client supports `POST /agent/bootstrap`, `POST
/agent/pairing-codes`, and `GET /agent/status`. Bootstrap is unauthenticated.
Pairing-code and status calls send `X-Agent-Id` and `Authorization: Bearer
<agentSecret>`. Non-2xx bridge responses must be converted to safe local
exceptions that do not include `agentSecret` values or auth headers. If
bootstrap omits `relayWebSocketUrl`, the connector derives `/agent/ws` from
`CODEX_AGENT_BRIDGE_BASE_URL`, using `ws` for `http` and `wss` for `https`.
The local `GET /api/agent/status` and `POST /api/agent/pairing-code` endpoints
are protected by the existing `CODEX_API_TOKEN` authentication. Status responses
must never include `agentSecret`; the pairing-code endpoint requires existing
agent state and returns `ok`, `pairingCode`, `expiresAt`, and `connectUrl`.
This repository implements connector-side support only; EME Chat pairing UI and
the full end-to-end product flow require separate validation outside this repo.

When local agent state is ready, the connector starts an outbound WebSocket
connection to `relayWebSocketUrl` in the background using `X-Agent-Id` and
`Authorization: Bearer <agentSecret>`. On open, it updates connection
diagnostics, persists `lastConnectedAt` when practical, and sends `agent.hello`
with safe metadata and capabilities. The WebSocket client sends heartbeat
`ping` messages, replies to bridge `ping` messages with `pong`, treats
`pong`/`agent.hello_ack` as last-seen diagnostics, buffers partial text frames
until `last=true`, and reconnects after close/error/connect failure with
exponential backoff capped by configuration.

Incoming `job.request` messages are accepted immediately, then dispatched to a
single local relay job executor. The connector supports `codex_readonly`,
`codex_verify`, and `codex_change`, caps requested timeouts by both connector
and Codex execution maximums, and returns safe `job.result` fields without raw
stderr, command-line details, or secrets. Unsupported tools, including
`codex_read_log`, return `UNSUPPORTED_TOOL` after `job.accepted`.

Relay jobs reuse the same internal execution path as the local Codex execution
endpoint. The connector shares the process runner and session service used by
the HTTP router, creates or reuses an internal saved session with session id
`agent_connector`, and calls `CodexService.execute(...)` directly rather than
making an HTTP request back to `127.0.0.1`. The internal session uses
`CODEX_AGENT_WORKING_DIRECTORY`, is persisted in `CODEX_SESSIONS_FILE`, and may
appear in `/api/sessions`. Relay executions therefore write the same
metadata-only execution history as normal Codex execution calls: prompt text is
omitted, stdout/stderr previews are bounded, and the session `lastUsedAt` value
is updated.

If the bridge disconnects while a relay job is running, V1 lets the local Codex
process continue. When the process completes, the connector attempts to send
`job.result` on the same WebSocket instance that delivered the original
`job.request`. It does not queue or replay the result after reconnect. If that
socket is closed, the result is effectively dropped from the connector side, and
bridge-side disconnect/stale-job handling remains authoritative. Local
execution history may still be written even when the bridge never receives the
final relay result.

## Connector Relay Manual Smoke Flow

This repository implements connector-side support only. A full product flow
still requires `eme-codex-bridge`, EME backend credentials, and later EME Chat
pairing UI. Until that UI exists, an operator can validate bridge relay mode
manually:

1. Start `../eme-codex-bridge` with MySQL, admin auth, EME credentials,
   `BRIDGE_AGENT_BOOTSTRAP_ENABLED=true`, and `BRIDGE_AGENT_RELAY_ENABLED=true`.
2. Start this server with `CODEX_AGENT_ENABLED=true`,
   `CODEX_AGENT_BRIDGE_BASE_URL=http://127.0.0.1:8787`,
   `CODEX_API_TOKEN`, and `CODEX_AGENT_WORKING_DIRECTORY` set to the local repo.
3. Call local `GET /api/agent/status` with the Codex API bearer token and verify
   the connector is active and connected.
4. Call local `POST /api/agent/pairing-code` with the Codex API bearer token and
   copy the returned one-time `pairingCode`.
5. Claim the code through bridge `POST /api/agent-pairings/claim` with
   `BRIDGE_ADMIN_TOKEN`, an operator-chosen `externalOwnerId`, and
   `expectedAgentType=codex`.
6. Create a bridge registration with `POST /api/registrations`,
   `routingMode=AGENT_RELAY`, the claimed `agentId`, the same
   `externalOwnerId`, and real EME session values.
7. Submit a bridge tool call such as
   `POST /tools/{bridgeKey}/codex_readonly` with a non-blank `message`.
8. Read status and final output through
   `POST /tools/{bridgeKey}/codex_read_log`.

The bridge dispatches `job.request` over `/agent/ws`; this connector sends
`job.accepted`, executes Codex locally, and returns `job.result` with safe
fields for the bridge to store and expose through `codex_read_log`.

## Implementation Milestones

### Milestone 1: Project Skeleton

- Maven project structure.
- Main class.
- Environment-based configuration.
- JSON utility.
- Basic router placeholder.
- README with build and run instructions.
- Repository-owned specification.

### Milestone 2: HTTP Foundation

- Route abstraction and request helpers.
- Consistent JSON error responses.
- Request method handling.
- Bearer token authentication for `/api/*` routes.
- Structured JSON error responses.

### Milestone 3: Codex CLI Status

- Remove authentication metadata from the public health response.
- Add reusable process runner with timeout support.
- Implement authenticated `GET /api/codex/status`.
- Run `<CODEX_CLI_PATH> --version` directly through `ProcessBuilder`.
- Return diagnostic `available: false` responses instead of HTTP 500 when Codex is unavailable.

### Milestone 4: Session Storage

- Session model.
- Session repository backed by `CODEX_SESSIONS_FILE`.
- Session creation, listing, retrieval, and deletion endpoints.
- Validation for names, working directories, and timeout limits.
- Atomic JSON writes where reasonably simple.

### Milestone 5: Read-Only API

- Add safe path containment checks.
- Read bounded project tree data.
- Read bounded text file content.
- Expose read-only git status and diff data.
- Add validation and structured error handling around inspection routes.

### Milestone 6: Controlled Codex Execution

- Implement the structured Codex execution endpoint.
- Enforce command path, working directory, and timeout constraints.
- Capture output safely.
- Persist execution metadata and previews to session history.
- Update `lastUsedAt` after execution attempts.
- Keep full stdout/stderr out of persisted history by default.

### Milestone 7: Hardening

- Add broader tests.
- Improve logging.
- Document operational guidance.
- Review security behavior before remote binding or multi-user access.

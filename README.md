# codex-api-server

A small Java/Maven HTTP API server for controlled local Codex integrations.

V1 is ready for local use with token-protected APIs, saved project sessions,
read-only inspection, and controlled non-interactive Codex execution.

See [SPEC.md](SPEC.md) for the repository-owned project specification.

The current implementation intentionally keeps the server narrow:

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

## Build

```bash
mvn -q test
mvn -q -DskipTests package
```

## Run For Development

```bash
CODEX_API_TOKEN=dev-token ./scripts/run-dev.sh
```

By default, the server listens on:

```text
http://127.0.0.1:8765
```

## Run Packaged Jar

Build the runnable jar:

```bash
mvn -q -DskipTests package
```

Run it:

```bash
CODEX_API_TOKEN=dev-token java -jar target/codex-api-server-0.1.0-SNAPSHOT.jar
```

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
| `CODEX_API_TOKEN` | unset | Bearer token for protected `/api/*` routes. |
| `CODEX_CLI_PATH` | `codex` | Path or command name for the Codex CLI status check. |
| `CODEX_SESSIONS_FILE` | `./data/sessions.json` | Session storage file path. |
| `CODEX_EXEC_DEFAULT_TIMEOUT` | `600` | Default execution timeout in seconds. |
| `CODEX_EXEC_MAX_TIMEOUT` | `1800` | Maximum execution timeout in seconds. |
| `CODEX_ALLOW_REMOTE_BIND` | `false` | Allows binding to non-local hosts when set to `true`. |
| `CODEX_MAX_REQUEST_BYTES` | `1000000` | Maximum JSON request body size for POST endpoints. |

Remote binding is disabled by default. If `CODEX_API_HOST` is set to a non-local
address, `CODEX_ALLOW_REMOTE_BIND=true` must also be set.

## Current Routes

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/health` | Returns a basic JSON health response. |
| `GET` | `/api/codex/status` | Checks whether the configured Codex CLI can run `<CODEX_CLI_PATH> --version`. |
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

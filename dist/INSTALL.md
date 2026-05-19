# Install codex-api-server

This distribution contains a runnable server JAR, startup scripts, and a sample
environment file. Maven is not required to run the extracted bundle.

## Requirements

- Java 17 or newer
- Codex CLI on `PATH` for Codex execution routes
- `curl` for the optional smoke test script

## Start

No configuration file is required for the default EME bridge connection.

```bash
cd /path/to/the/project/codex-should-work-on
/path/to/codex-api-server-0.1.0-SNAPSHOT/bin/codex-api-server
```

The connector uses `https://emebridge.eagma.com` by default, bootstraps a
local agent identity, and prints pairing details when the bridge returns them.
It requests a fresh pairing code on each startup, including after the local
identity has already been created.
The default Codex working directory is the directory where you run the launcher.

To enable protected local `/api/*` routes, create a local token:

```bash
cp .env.example .env
chmod 600 .env
vi .env
/path/to/codex-api-server-0.1.0-SNAPSHOT/bin/codex-api-server
```

The server listens on `http://127.0.0.1:8765` unless `CODEX_API_HOST` or
`CODEX_API_PORT` is changed.

If that port is already in use, the launcher shows the existing listener and
asks whether to restart it. Press Enter to stop the existing listener and start
again, or type anything else to keep the current process. In non-interactive
scripts, set `CODEX_API_RESTART_EXISTING=true` to restart automatically.

`codex_readonly` relay jobs run with Codex CLI's `read-only` sandbox by
default, while mutable relay jobs run with `workspace-write`. For trusted local
testing where you want relay jobs to run without filesystem sandboxing, start
the launcher with:

```bash
CODEX_AGENT_READONLY_SANDBOX_MODE=danger-full-access CODEX_AGENT_SANDBOX_MODE=danger-full-access /path/to/codex-api-server-0.1.0-SNAPSHOT/bin/codex-api-server
```

Run the smoke test from another terminal after setting `CODEX_API_TOKEN`:

```bash
CODEX_API_TOKEN=<same-token-from-env> ./scripts/smoke-test.sh
```

On Windows, run:

```cmd
cd C:\path\to\the\project\codex-should-work-on
C:\path\to\codex-api-server-0.1.0-SNAPSHOT\bin\codex-api-server.cmd
```

## Configuration

All configuration is provided through environment variables. Use `.env.example`
as the starting point when you need to override defaults.

Session history defaults to `./data/sessions.json` inside the extracted
distribution directory. The local agent identity defaults to
`~/.codex/eme-codex-agent/agent.json` so it survives package extraction or
launcher folder changes.

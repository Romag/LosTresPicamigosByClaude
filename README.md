# Los Tres Picamigos

A local, Java-based **MCP server** that lets three CLI coding agents — **Codex** (`codex`),
**Claude Code** (`claude`), and **Antigravity** (`agy`) — delegate work to one another.

You drive one agent interactively (the *orchestrator*); through this server it can launch the *other*
agents **headlessly via their CLIs** (using each subscription — **no API tokens**), capture their output,
and coordinate. It automates the manual copy-paste loop of cross-agent review and plan→implement hand-offs,
and helps you spread work across three separate 5-hour usage windows.

> The name is a pun on *Los Tres Amigos* ("three friends") — here, three friendly **competitors**.

## What it does

- **Delegate** a task to a specific agent (or let it **auto-route** by task type) and get the result back.
- **Fan out** async jobs (e.g. fire parallel code reviews to the other two agents) and poll for results.
- **Budget-aware**: tracks each agent's usage in a rolling 5-hour window and avoids agents that hit a limit.
- **Capability-based routing**: implementation/reasoning prefers Codex/Claude; reviews fan out to everyone.
- **Prompt library**: save and reuse standard delegation prompts with `{variable}` placeholders.
- **Shared note board**: agents leave review comments visible to all.

All three agents connect to **one shared server**, so jobs, usage, prompts, and the note board are shared.

## Requirements

- **Java 25+** (build targets `--release 25`).
- The agent CLIs you want to use on your `PATH`: `codex`, `claude`, `agy`.
- Maven is **not** required to run — use the bundled `./mvnw` to build.

## Build & run

```bash
./mvnw clean package          # produces target/picamigos-mcp.jar
java -jar target/picamigos-mcp.jar --repo /path/to/your/project --port 8765
# or use the launchers:  ./run.sh --repo .    |    run.cmd --repo .
```

The server binds **loopback only** (`127.0.0.1`) and serves MCP over Streamable HTTP at `/mcp`.

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--host <addr>` | `127.0.0.1` | bind address (loopback only) |
| `--port <n>` | `8765` | bind port |
| `--repo <dir>` | current dir | working directory delegated agents run in |
| `--config <file>` | bundled | agent config override (see below) |
| `--prompts-file <file>` | `<repo>/.picamigos/prompts.json` | prompt library storage |
| `--window-hours <n>` | `5` | usage-window length |
| `--dangerously-skip-permissions` | off | default delegations to `edit` (auto-approve) mode |

## Connect your agents

Run the server, then register it as an MCP server in each CLI (verify exact syntax for your version):

- **Claude Code:** `claude mcp add --transport http picamigos http://127.0.0.1:8765/mcp`
- **Codex** — in `~/.codex/config.toml`:
  ```toml
  [mcp_servers.picamigos]
  url = "http://127.0.0.1:8765/mcp"
  ```
- **Antigravity** — in `~/.gemini/config/mcp_config.json`:
  ```json
  { "mcpServers": { "picamigos": { "serverUrl": "http://127.0.0.1:8765/mcp" } } }
  ```
  (note: agy uses `serverUrl`, not `url`.)

See [docs/client-setup.md](docs/client-setup.md) for details and [docs/routing.md](docs/routing.md) for routing.

## MCP tools

| Tool | Purpose |
|---|---|
| `list_agents` | configured agents, availability, model, capability scores |
| `agent_status` | per-agent budget/availability in the rolling window |
| `recommend_agent` | best agent(s) for a task type (`review` → all available) |
| `delegate` | run an agent and block for the result |
| `start_job` / `get_job` / `list_jobs` / `cancel_job` | async jobs + fan-out |
| `save_prompt` / `get_prompt` / `list_prompts` / `delete_prompt` | prompt library |
| `post_note` / `get_notes` | shared comment board |

`delegate` / `start_job` take either `agent` or `task_type` (auto-routed), and either `prompt` or
`prompt_name` (+ `variables`), with an optional `mode` (`ask` = read-only, `edit` = may modify the repo).

## Configuration

Agent commands, modes, capability scores, and limit-detection patterns live in
[`agents.default.json`](src/main/resources/agents.default.json). Override any subset via `--config <file>`
(deep-merged over the defaults) — e.g. to retune capability scores or CLI flags without rebuilding.

## Known limitations

- **Antigravity (`agy`) returns empty output in non-TTY mode on Windows** — its TUI renderer writes to the
  console, bypassing the captured pipe (even `agy models` yields nothing over a pipe). Codex and Claude work
  fully. Capturing agy would need a pseudo-console (ConPTY) bridge — out of scope for v1. See
  [docs/cli-notes.md](docs/cli-notes.md).
- State (jobs, usage ledger, note board) is **in-memory** and lost on restart. Only the prompt library persists.
- No authentication and loopback-only by design (no tokens, no remote exposure).

## Development

Built in small, verifiable steps. `./mvnw verify` runs all unit + integration tests; the integration tests
spin up the real HTTP server and drive it with the MCP SDK client against a fake agent (no real CLI / network).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

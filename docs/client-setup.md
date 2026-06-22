# Client setup

How to run the Picamigos server and connect each agent CLI to it. The server is a single long-running
process; all agents connect to the same instance.

## 1. Start the server

```bash
java -jar target/picamigos-mcp.jar --repo /path/to/your/project --port 8765
```

Leave it running. It logs (to stderr) a line like:

```
Picamigos MCP server listening on http://127.0.0.1:8765/mcp
```

`--repo` is the working directory delegated agents operate in — point it at the project you're working on.

## 2. Register the server in each agent

> Verify the exact registration syntax for your installed CLI version; the commands below match the
> versions documented in [cli-notes.md](cli-notes.md).

### Claude Code
```bash
claude mcp add --transport http picamigos http://127.0.0.1:8765/mcp
claude mcp list        # confirm "picamigos" is listed
```

### Codex
Add to `~/.codex/config.toml`:
```toml
[mcp_servers.picamigos]
url = "http://127.0.0.1:8765/mcp"
```
Then in a Codex session, `/mcp` should list the picamigos tools.

### Antigravity
Add to `~/.gemini/config/mcp_config.json` (global) or a workspace `.agents/mcp_config.json`:
```json
{
  "mcpServers": {
    "picamigos": { "serverUrl": "http://127.0.0.1:8765/mcp" }
  }
}
```
Antigravity uses `serverUrl` (not `url`) for HTTP MCP servers.

## 3. Use it

From whichever agent you're driving:

- **Check capacity first:** `agent_status` — see which agents have budget left in their window.
- **Pick an agent:** `recommend_agent` with a `task_type` (`implement`, `reason`, `plan`, `review`, `test`, `docs`).
- **Delegate (blocking):** `delegate` with `agent` (or `task_type`) and `prompt` (or `prompt_name`).
- **Fan out (parallel reviews):** call `start_job` for each reviewer, then poll `get_job` until each is done.
- **Leave/read comments:** `post_note` / `get_notes` on a shared `thread`.

### Example: one implements, two review

1. Drive Claude. It implements a change in `--repo`.
2. Claude calls `recommend_agent {task_type: "review"}` → gets `codex` and `antigravity`.
3. Claude calls `start_job {agent: "codex", prompt: "Review the staged diff for bugs", mode: "ask"}` and
   `start_job {agent: "antigravity", ...}`.
4. Claude polls `get_job` for each until `DONE`, collects both reviews, and `post_note`s a summary to
   thread `review/x`.

> **Note on Antigravity:** in headless/non-TTY mode on Windows, `agy` currently returns empty output
> (see [cli-notes.md](cli-notes.md)). Codex and Claude delegations work fully.

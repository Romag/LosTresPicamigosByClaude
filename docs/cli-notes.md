# CLI notes — verified headless flags

Verified on this machine on 2026-06-22 by running each CLI's `--help` and a one-shot smoke test.
These are the facts the default `agents.default.json` is built from. Flags are config, not code — tune
them in a `--config` override without recompiling.

## codex (Codex / ChatGPT) — ✅ works headless
- Version: `codex-cli 0.137.0` (`codex.exe`).
- Non-interactive: `codex exec [OPTIONS] [PROMPT]`. **If no prompt arg is given (or `-`), the prompt is
  read from stdin.** We write the prompt to stdin and close it (EOF), so no prompt arg is needed.
- Sandbox/permissions (exec is non-interactive and does not prompt): `-s, --sandbox
  read-only|workspace-write|danger-full-access`.
- Other useful flags: `--color never` (cleaner output), `--skip-git-repo-check` (run outside a git repo),
  `-C/--cd <dir>` (working dir — we set cwd via ProcessBuilder instead), `--json` (JSONL events),
  `-o/--output-last-message <file>` (final message only), `-m/--model`.
- Smoke test (PONG): `echo "Reply with exactly: PONG" | codex exec --color never --skip-git-repo-check -s read-only` → prints transcript ending in `PONG`, exit 0.
- Note: exec stdout includes the whole transcript (user + assistant + token count), not just the final
  message. Acceptable for v1; could be narrowed later with `-o <file>` or `--json` parsing.

## claude (Claude Code) — ✅ works headless
- Non-interactive: `claude -p/--print [prompt]`; prompt may be an arg or piped via stdin (stdin ≤10MB).
- Permissions: `--permission-mode <mode>` (e.g. `plan` = read/analyze only, `acceptEdits` = auto-accept
  edits), `--allowedTools <list>`, `--dangerously-skip-permissions`.
- `--model`, `--output-format text|json|stream-json` (we use default text for clean output).
- Smoke test (PONG): `echo "Reply with exactly: PONG" | claude -p --dangerously-skip-permissions` → `PONG`, exit 0.
- Note: the npm Git-Bash shim `claude` (no extension) is broken on this machine, but `claude.cmd` works;
  `ExecutableResolver` resolves `claude.cmd` and the launcher runs it via `cmd.exe /c`.

## agy (Antigravity / Gemini 3.5 Flash) — ✅ works via a pseudo-terminal (PTY)
- Version: `agy 1.0.7` (`agy.exe`, Go binary).
- Non-interactive: `agy -p/--print/--prompt "<prompt>"` (prompt as arg); auto-approve with
  `--dangerously-skip-permissions`; `--sandbox` (terminal restrictions); `--print-timeout <dur>` (default 5m);
  `--model`. **There is NO `--headless` or `--approve` flag** (contrary to some online guides).
- **The catch:** `agy` is a TUI renderer that writes to a **console**, not a pipe. Under `ProcessBuilder`
  (which pipes stdout) it produces **zero bytes** — even `agy models` returns nothing over a pipe, and
  forcing `TERM=dumb`/`CI=1` just makes it hang.
- **The fix:** the launcher runs agy under a **pseudo-terminal** via [pty4j](https://github.com/JetBrains/pty4j)
  (ConPTY on Windows 10+, `forkpty` on Unix). agy then believes it has a real terminal and streams its
  output, which we capture and clean (`TerminalText` strips ANSI escapes and resolves `\r` line-overwrites).
  Verified: a real `delegate` to antigravity returns thousands of chars of readable output. Enabled by the
  per-agent `"pty": true` config flag (set for antigravity in `agents.default.json`).
- Note: agy in `-p` mode is highly agentic — it narrates its exploration (reading files, running commands)
  before its conclusion, so its output is verbose. The launcher's timeout still bounds the run.

## Usage / budget signals
- No CLI reliably reports remaining 5-hour budget in headless mode (claude `usage --json` unshipped; codex
  `exec --json` `rate_limits` is null). Budget tracking is therefore duration/count based (the usage
  ledger, M6); `usageParse` is left `none` by default.

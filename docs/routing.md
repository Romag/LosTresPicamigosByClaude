# Routing & budget

Delegation is **not random**. `recommend_agent` (and `delegate`/`start_job` when given a `task_type`
instead of an explicit `agent`) picks the best-fit agent using capability scores and current budget.

## Capability scores

Each agent has a per-task-type score in [`agents.default.json`](../src/main/resources/agents.default.json).
Defaults reflect that Codex and Claude are stronger at implementation/reasoning than Gemini 3.5 Flash:

| task_type | codex | claude | antigravity |
|---|---|---|---|
| implement | 9 | 9 | 5 |
| reason | 9 | 9 | 6 |
| plan | 8 | 9 | 6 |
| review | 8 | 9 | 7 |
| test | 8 | 8 | 6 |
| docs | 7 | 8 | 7 |

Retune these via a `--config` override (e.g. if you switch Antigravity to Gemini Pro or a Claude model,
raise its scores) — no rebuild needed.

## Routing rules

1. Rank candidate agents by their score for the requested `task_type` (descending).
2. Exclude agents that are **not installed** (executable not on `PATH`).
3. Exclude agents that are currently **rate-limited** (see budget below).
4. Break ties by **most remaining budget** (least active time used in the window).
5. For `task_type = "review"`, return **all** available agents (so you can fan out reviews to everyone).

An explicit `agent` always overrides routing.

## Budget tracking

No CLI reliably reports remaining 5-hour budget in headless mode, so the server tracks usage itself:

- Every delegation is recorded against the agent in a rolling window (default 5 hours, `--window-hours`).
- `agent_status` reports, per agent: `state` (`OK` / `RATE_LIMITED`), jobs and active-seconds in the window,
  estimated cost (if ever available), `estimatedAvailableAt`, and `lastActivityAt`.
- If a run's output matches a configured **limit pattern** (e.g. "usage limit reached") and the run failed,
  the launcher classifies it `RATE_LIMITED`; the agent is then skipped by routing until `hitTime + window`.

## Handling interruptions

If an agent is cut off mid-task (rate-limited or timed out), the job preserves its **prompt** and
**partial output**, and `get_job` exposes `lastActivityAt` for liveness. To continue, re-`delegate` the
remainder to a different agent (passing the original prompt + partial output as context), or use the agent's
native resume. Automatic resume is intentionally out of scope for v1.

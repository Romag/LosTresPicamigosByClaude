package com.picamigos.usage;

import java.time.Instant;

/**
 * A snapshot of one agent's usage within the rolling window — the data behind the {@code agent_status}
 * MCP tool, and the budget signal the router uses.
 *
 * @param agent                canonical agent name
 * @param state                availability state
 * @param windowJobs           number of jobs started within the window
 * @param windowActiveSeconds  total active run-seconds within the window
 * @param estWindowCostUsd     summed reported cost within the window, or null if none reported
 * @param estimatedAvailableAt when a rate-limited agent is estimated to be available again, or null
 * @param lastActivityAt       end time of the most recent run, or null if no runs recorded
 */
public record AgentUsage(
        String agent,
        UsageState state,
        int windowJobs,
        long windowActiveSeconds,
        Double estWindowCostUsd,
        Instant estimatedAvailableAt,
        Instant lastActivityAt) {

    public boolean rateLimited() {
        return state == UsageState.RATE_LIMITED;
    }
}

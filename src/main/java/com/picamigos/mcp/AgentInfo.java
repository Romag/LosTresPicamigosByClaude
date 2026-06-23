package com.picamigos.mcp;

import java.util.Map;

/**
 * Summary of a configured agent, returned by the {@code list_agents} tool.
 *
 * @param name         canonical agent name
 * @param displayName  human-readable name
 * @param available    whether the agent's executable resolves on PATH
 * @param enabled      whether the agent is eligible for auto-routing (explicit delegate still works)
 * @param model        informational model label
 * @param capabilities per-task-type capability scores
 */
public record AgentInfo(
        String name,
        String displayName,
        boolean available,
        boolean enabled,
        String model,
        Map<String, Integer> capabilities) {
}

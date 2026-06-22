package com.picamigos.routing;

/**
 * A routing recommendation: which agent to use for a task, its capability score, and a short
 * human-readable reason.
 *
 * @param agent  canonical agent name
 * @param score  capability score for the requested task type
 * @param reason concise explanation (capability + current budget usage)
 */
public record AgentRecommendation(String agent, int score, String reason) {
}

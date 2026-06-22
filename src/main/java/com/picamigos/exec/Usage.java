package com.picamigos.exec;

/**
 * Best-effort usage/cost information parsed from an agent's output. All fields are nullable; a value
 * is present only when the agent's output format exposed it. Budget tracking does not depend on this
 * (it is primarily duration/count based) — usage here is opportunistic.
 *
 * @param costUsd      reported cost in USD, if any
 * @param inputTokens  input tokens, if any
 * @param outputTokens output tokens, if any
 * @param totalTokens  total tokens, if any
 */
public record Usage(Double costUsd, Long inputTokens, Long outputTokens, Long totalTokens) {
}

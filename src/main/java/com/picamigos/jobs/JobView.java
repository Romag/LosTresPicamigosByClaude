package com.picamigos.jobs;

import java.time.Instant;

/**
 * An immutable snapshot of a {@link Job}, returned by the MCP tools (get_job / list_jobs / delegate).
 *
 * @param id             job id
 * @param agent          canonical agent name
 * @param mode           run mode (ask / edit)
 * @param status         current status
 * @param output         output so far (partial while running, final once terminal), length-capped
 * @param truncated      whether {@code output} was truncated
 * @param exitCode       process exit code, or null if not finished / never started
 * @param startedAt      when the job started
 * @param lastActivityAt timestamp of the most recent output activity (liveness signal)
 * @param finishedAt     when the job reached a terminal state, or null if still running
 * @param durationMillis elapsed time (to finish if terminal, else to now)
 */
public record JobView(
        String id,
        String agent,
        String mode,
        JobStatus status,
        String output,
        boolean truncated,
        Integer exitCode,
        Instant startedAt,
        Instant lastActivityAt,
        Instant finishedAt,
        long durationMillis) {
}

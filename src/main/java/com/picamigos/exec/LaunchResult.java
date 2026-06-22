package com.picamigos.exec;

/**
 * The result of running a delegated agent CLI to completion (or timeout).
 *
 * @param outcome        how the run ended
 * @param exitCode       process exit code, or -1 if it was killed (timeout/cancel)
 * @param output         the agent's stdout, ANSI-stripped and length-capped (primary result)
 * @param truncated      whether {@link #output} was truncated
 * @param durationMillis wall-clock duration of the run
 * @param usage          best-effort usage/cost, or null if unavailable
 * @param stderrTail     a short tail of stderr, useful for diagnosing failures
 */
public record LaunchResult(
        Outcome outcome,
        int exitCode,
        String output,
        boolean truncated,
        long durationMillis,
        Usage usage,
        String stderrTail) {

    /** True iff the run completed successfully (exit 0). */
    public boolean success() {
        return outcome == Outcome.DONE;
    }
}

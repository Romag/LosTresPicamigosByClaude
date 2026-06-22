package com.picamigos.exec;

/**
 * Classification of how a delegated agent run ended.
 *
 * <p>{@link #CANCELLED} is set by the job layer (not the launcher itself) when a caller explicitly
 * cancels a running job.
 */
public enum Outcome {
    /** Process exited 0. */
    DONE,
    /** Process exited non-zero and did not look rate-limited. */
    FAILED,
    /** Process exceeded its timeout and was force-killed. */
    TIMEOUT,
    /** Process failed and its output matched a configured usage/rate-limit pattern. */
    RATE_LIMITED,
    /** Run was explicitly cancelled by a caller. */
    CANCELLED
}

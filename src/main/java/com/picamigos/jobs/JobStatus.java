package com.picamigos.jobs;

import com.picamigos.exec.Outcome;

/** Lifecycle state of a delegation job. */
public enum JobStatus {
    RUNNING,
    DONE,
    FAILED,
    TIMEOUT,
    RATE_LIMITED,
    CANCELLED;

    /** Whether this is a terminal (finished) state. */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /** Maps a launcher {@link Outcome} to the corresponding terminal job status. */
    public static JobStatus fromOutcome(Outcome outcome) {
        return switch (outcome) {
            case DONE -> DONE;
            case FAILED -> FAILED;
            case TIMEOUT -> TIMEOUT;
            case RATE_LIMITED -> RATE_LIMITED;
            case CANCELLED -> CANCELLED;
        };
    }
}

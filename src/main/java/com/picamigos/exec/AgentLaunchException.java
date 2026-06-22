package com.picamigos.exec;

/**
 * Thrown when an agent run cannot even be started — e.g. the executable is not found on PATH, or the
 * process fails to spawn. Distinct from a run that starts and then fails (which is reported via a
 * {@link LaunchResult} with a {@code FAILED}/{@code RATE_LIMITED}/{@code TIMEOUT} outcome).
 */
public class AgentLaunchException extends RuntimeException {

    public AgentLaunchException(String message) {
        super(message);
    }

    public AgentLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}

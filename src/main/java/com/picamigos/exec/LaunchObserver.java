package com.picamigos.exec;

/**
 * Optional callbacks invoked by {@link AgentLauncher} during a run. Used by the job layer to capture
 * the live process handle (for cancellation) and stream partial output (for liveness / progress).
 *
 * <p>All methods have no-op defaults; chunk callbacks may be invoked from background pump threads, so
 * implementations must be thread-safe.
 */
public interface LaunchObserver {

    /** Called once, immediately after the child process starts. */
    default void onStart(Process process) {
    }

    /** Called with each raw stdout chunk as it is read. */
    default void onStdout(String chunk) {
    }

    /** Called with each raw stderr chunk as it is read. */
    default void onStderr(String chunk) {
    }

    /** A no-op observer. */
    LaunchObserver NONE = new LaunchObserver() {
    };
}

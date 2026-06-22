package com.picamigos.config;

/**
 * How a delegated agent's prompt is delivered to its CLI process.
 *
 * <ul>
 *   <li>{@link #STDIN} — the prompt is written to the child process's stdin, then stdin is closed.</li>
 *   <li>{@link #ARG} — the prompt is appended as the final command-line argument, and stdin is closed
 *       immediately (with EOF).</li>
 * </ul>
 *
 * In both cases the launcher always closes the child's stdin so non-TTY CLIs (codex, agy) do not hang
 * waiting for input.
 */
public enum PromptVia {
    STDIN,
    ARG
}

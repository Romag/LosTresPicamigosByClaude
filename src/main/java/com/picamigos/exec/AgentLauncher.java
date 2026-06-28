package com.picamigos.exec;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.PromptVia;
import com.picamigos.util.OutputTruncator;
import com.picamigos.util.TerminalText;
import com.pty4j.PtyProcessBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a delegated agent's CLI as a child process and captures its result.
 *
 * <p>Implements the cross-platform launcher rules:
 * <ol>
 *   <li>resolve the executable on PATH; wrap {@code .cmd}/{@code .bat} in {@code cmd.exe /c};</li>
 *   <li>always give the child stdin EOF (write the prompt then close, or close immediately for
 *       {@code promptVia=arg}) so non-TTY CLIs do not hang;</li>
 *   <li>run with the working directory set to the shared repo;</li>
 *   <li>drain stdout and stderr on separate threads;</li>
 *   <li>enforce a timeout, force-killing on expiry;</li>
 *   <li>strip ANSI and cap output length;</li>
 *   <li>classify the outcome (done / failed / timeout / rate-limited).</li>
 * </ol>
 */
public final class AgentLauncher {

    private static final Logger log = LoggerFactory.getLogger(AgentLauncher.class);

    /** Hard safety cap on retained raw chars per stream, to avoid OOM on pathological output. */
    private static final int RAW_SAFETY_CAP = 8_000_000;

    /** Grace period to let a force-killed process actually die. */
    private static final int KILL_GRACE_SECONDS = 5;

    /** Max time to wait for output pump threads (kept short so kills return promptly on Windows). */
    private static final long PUMP_JOIN_MILLIS = 1500;

    private final Path repoDir;

    public AgentLauncher(Path repoDir) {
        this.repoDir = Objects.requireNonNull(repoDir, "repoDir").toAbsolutePath();
    }

    /**
     * Runs {@code agent} with the given prompt and mode, blocking until completion or timeout.
     *
     * @param agent           resolved agent configuration
     * @param prompt          the full prompt text
     * @param mode            mode key (e.g. {@code ask} / {@code edit}); selects extra args
     * @param timeoutOverride per-run timeout in seconds, or null to use the agent default
     * @param observer        optional callbacks (process handle, live chunks); may be null
     * @return the launch result
     * @throws AgentLaunchException if the process cannot be started
     */
    public LaunchResult run(AgentConfig agent, String prompt, String mode,
                            Integer timeoutOverride, LaunchObserver observer) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(prompt, "prompt");
        LaunchObserver obs = observer == null ? LaunchObserver.NONE : observer;

        ExecutableResolver.Resolution resolution = ExecutableResolver.resolve(agent.executable())
                .orElseThrow(() -> new AgentLaunchException(
                        "Executable not found on PATH for agent '" + agent.displayName()
                                + "': " + agent.executable()));

        List<String> command = buildCommand(agent, resolution, mode, prompt);
        int timeoutSeconds = (timeoutOverride != null && timeoutOverride > 0)
                ? timeoutOverride : agent.timeoutSeconds();

        log.debug("Launching {} (mode={}, timeout={}s): {}",
                agent.displayName(), mode, timeoutSeconds, command);

        boolean pty = agent.pty();
        long startNanos = System.nanoTime();
        Process process;
        try {
            process = pty
                    ? startPty(command)
                    : new ProcessBuilder(command).directory(repoDir.toFile()).start();
        } catch (IOException e) {
            throw new AgentLaunchException("Failed to start " + agent.executable() + ": " + describe(e), e);
        }
        obs.onStart(process);

        if (pty) {
            // PTY agents take the prompt as an argument; close the master's input so the child gets EOF.
            closeQuietly(process.getOutputStream());
        } else {
            feedStdin(process, agent.promptVia(), prompt);
        }

        // A PTY merges stderr into its single stream, so there is no separate stderr to drain.
        StreamPump outPump = new StreamPump(process.getInputStream(), obs, true);
        Thread outThread = Thread.ofVirtual().name("agent-stdout").start(outPump);
        StreamPump errPump = pty ? null : new StreamPump(process.getErrorStream(), obs, false);
        Thread errThread = errPump == null ? null : Thread.ofVirtual().name("agent-stderr").start(errPump);

        boolean finished;
        boolean interrupted = false;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            finished = false;
            Thread.currentThread().interrupt();
        }

        Outcome outcome;
        int exitCode;
        if (!finished) {
            process.destroyForcibly();
            awaitExit(process);
            exitCode = -1;
            outcome = interrupted ? Outcome.CANCELLED : Outcome.TIMEOUT;
        } else {
            exitCode = process.exitValue();
            outcome = null; // classified below after output is collected
        }

        // Briefly join the pumps. On normal completion they EOF within milliseconds, so this captures
        // all output. After ANY force-kill (timeout OR external cancel) the blocked reads may not
        // unblock promptly on Windows, so we cap the wait and proceed — the output is already captured
        // live in the thread-safe buffers, and a still-blocked pump is a cheap virtual thread that
        // exits on its own once the pipe finally EOFs.
        join(outThread, PUMP_JOIN_MILLIS);
        if (errThread != null) {
            join(errThread, PUMP_JOIN_MILLIS);
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        String cleanStdout = TerminalText.render(outPump.text());
        String cleanStderr = errPump == null ? "" : TerminalText.render(errPump.text());

        if (outcome == null) {
            outcome = classify(exitCode, cleanStdout, cleanStderr, agent.limitPatterns());
        }

        OutputTruncator.Result truncated = OutputTruncator.truncate(cleanStdout, agent.maxOutputChars());

        return new LaunchResult(
                outcome,
                exitCode,
                truncated.text(),
                truncated.truncated(),
                durationMillis,
                null, // usage parsing is opt-in / future work; budget tracking is duration-based
                tail(cleanStderr, 1000));
    }

    /** Builds the full command line: [cmd /c] executable + baseArgs + modeArgs [+ prompt]. */
    private static List<String> buildCommand(AgentConfig agent, ExecutableResolver.Resolution resolution,
                                             String mode, String prompt) {
        List<String> command = new ArrayList<>();
        if (resolution.needsCmdWrapper()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(resolution.executable().toString());
        command.addAll(agent.baseArgs());
        command.addAll(agent.modeArgs(mode));
        if (agent.promptVia() == PromptVia.ARG) {
            command.add(prompt);
        }
        return command;
    }

    /**
     * Starts the command under a pseudo-terminal so TUI CLIs produce capturable output.
     *
     * <p>No single Windows PTY backend works in every launch context (the default auto-detected
     * backend works under surefire/most shells but has failed in some server launch contexts; the
     * explicit ConPTY backend works in those but fails under surefire). So on Windows we try the
     * default first and fall back to explicit ConPTY — PTY creation fails fast, so the fallback is
     * cheap and whichever backend the environment supports is used.
     */
    private Process startPty(List<String> command) throws IOException {
        try {
            return buildPty(command, false).start();
        } catch (IOException defaultFailure) {
            if (!isWindows()) {
                throw defaultFailure;
            }
            log.warn("Default PTY backend failed, retrying with ConPTY: {}", describe(defaultFailure));
            try {
                return buildPty(command, true).start();
            } catch (IOException conPtyFailure) {
                conPtyFailure.addSuppressed(defaultFailure);
                throw conPtyFailure;
            }
        }
    }

    private PtyProcessBuilder buildPty(List<String> command, boolean forceConPty) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putIfAbsent("TERM", "xterm-256color");
        PtyProcessBuilder builder = new PtyProcessBuilder()
                .setCommand(command.toArray(new String[0]))
                .setEnvironment(env)
                .setDirectory(repoDir.toString())
                .setInitialColumns(200)
                .setInitialRows(50)
                .setRedirectErrorStream(true);
        if (forceConPty) {
            builder.setUseWinConPty(true).setCygwin(false);
        }
        return builder;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** Flattens an exception's message and its cause chain, for diagnosable launch-failure reporting. */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
        for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
            sb.append(" | caused by: ").append(c);
        }
        return sb.toString();
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /** Writes the prompt to stdin (if {@code STDIN}) and always closes stdin to deliver EOF. */
    private static void feedStdin(Process process, PromptVia promptVia, String prompt) {
        try (OutputStream stdin = process.getOutputStream()) {
            if (promptVia == PromptVia.STDIN) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
                stdin.flush();
            }
            // Closing the stream (try-with-resources) delivers EOF in both cases.
        } catch (IOException e) {
            // The child may have already exited / closed its stdin; not fatal.
            log.debug("Could not write prompt to stdin (process may have exited): {}", e.getMessage());
        }
    }

    /**
     * Classifies a completed run. A run is considered rate-limited only when it failed (non-zero exit)
     * AND its output matches a configured limit pattern — this avoids false positives on successful
     * runs whose output merely mentions rate limits.
     */
    static Outcome classify(int exitCode, String stdout, String stderr, List<String> limitPatterns) {
        if (exitCode == 0) {
            return Outcome.DONE;
        }
        if (matchesAny(limitPatterns, stdout, stderr)) {
            return Outcome.RATE_LIMITED;
        }
        return Outcome.FAILED;
    }

    private static boolean matchesAny(List<String> patterns, String stdout, String stderr) {
        for (String regex : patterns) {
            try {
                Pattern p = Pattern.compile(regex);
                if (p.matcher(stdout).find() || p.matcher(stderr).find()) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                log.warn("Ignoring invalid limit pattern '{}': {}", regex, e.getMessage());
            }
        }
        return false;
    }

    private static String tail(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(text.length() - maxChars);
    }

    private static void awaitExit(Process process) {
        try {
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drains an input stream on its own thread, accumulating text and forwarding chunks to the observer. */
    private static final class StreamPump implements Runnable {
        private final InputStream in;
        private final LaunchObserver observer;
        private final boolean stdout;
        private final StringBuilder buffer = new StringBuilder();

        StreamPump(InputStream in, LaunchObserver observer, boolean stdout) {
            this.in = in;
            this.observer = observer;
            this.stdout = stdout;
        }

        @Override
        public void run() {
            char[] chunk = new char[4096];
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = reader.read(chunk)) != -1) {
                    String text = new String(chunk, 0, n);
                    if (stdout) {
                        observer.onStdout(text);
                    } else {
                        observer.onStderr(text);
                    }
                    synchronized (buffer) {
                        if (buffer.length() < RAW_SAFETY_CAP) {
                            buffer.append(text);
                        }
                        // Past the safety cap we keep reading (to avoid blocking the child) but discard.
                    }
                }
            } catch (IOException e) {
                // Stream closed (process ended); stop draining.
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }
}

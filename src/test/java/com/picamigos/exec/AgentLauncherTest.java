package com.picamigos.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.PromptVia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link AgentLauncher}, driven by {@link com.picamigos.test.FakeAgent} so they
 * are independent of the real CLIs and the network. A hard 30s per-test timeout means a launcher that
 * fails to close the child's stdin (causing FakeAgent to block on read) surfaces as a test failure.
 */
@Timeout(30)
class AgentLauncherTest {

    @TempDir
    Path repo;

    private AgentLauncher launcher() {
        return new AgentLauncher(repo);
    }

    /** Builds an AgentConfig that runs FakeAgent via the current JVM and classpath. */
    private static AgentConfig fake(PromptVia via, int timeoutSeconds, int maxOutputChars,
                                    List<String> limitPatterns, String... controlFlags) {
        List<String> baseArgs = new ArrayList<>();
        baseArgs.add("-cp");
        baseArgs.add(System.getProperty("java.class.path"));
        baseArgs.add("com.picamigos.test.FakeAgent");
        baseArgs.addAll(Arrays.asList(controlFlags));
        return new AgentConfig(
                "Fake Agent", "java", "fake",
                List.of(), baseArgs, via,
                Map.of("ask", List.of(), "edit", List.of()),
                Map.of(), "none", limitPatterns,
                timeoutSeconds, maxOutputChars, true, false);
    }

    @Test
    void stdinPromptIsDeliveredAndRunCompletes() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 200_000, List.of());
        LaunchResult r = launcher().run(agent, "hello world", "ask", null, null);

        assertEquals(Outcome.DONE, r.outcome());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("PROMPT:hello world"), () -> "output was: " + r.output());
        assertFalse(r.truncated());
        // Completing well under the timeout proves stdin was closed (EOF) rather than left open.
        assertTrue(r.durationMillis() < 30_000);
    }

    @Test
    void argPromptIsPassedAsFinalArgument() {
        AgentConfig agent = fake(PromptVia.ARG, 30, 200_000, List.of(), "--from-arg");
        LaunchResult r = launcher().run(agent, "arg-prompt-123", "ask", null, null);

        assertEquals(Outcome.DONE, r.outcome());
        assertTrue(r.output().contains("PROMPT:arg-prompt-123"), () -> "output was: " + r.output());
    }

    @Test
    void timeoutForceKillsTheProcess() {
        AgentConfig agent = fake(PromptVia.STDIN, 1, 200_000, List.of(), "--sleep", "10000");
        // Use a stable (non-temp) working dir: the killed child's cwd handle can briefly linger on
        // Windows, which would make @TempDir cleanup fail. In production the repo is never deleted.
        AgentLauncher killLauncher = new AgentLauncher(Path.of(".").toAbsolutePath());
        LaunchResult r = killLauncher.run(agent, "p", "ask", null, null);

        assertEquals(Outcome.TIMEOUT, r.outcome());
        assertEquals(-1, r.exitCode());
        assertTrue(r.durationMillis() >= 1_000 && r.durationMillis() < 10_000);
    }

    @Test
    void nonZeroExitIsFailed() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 200_000, List.of(), "--fail", "3");
        LaunchResult r = launcher().run(agent, "p", "ask", null, null);

        assertEquals(Outcome.FAILED, r.outcome());
        assertEquals(3, r.exitCode());
    }

    @Test
    void limitMessageOnFailureIsRateLimited() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 200_000, List.of("(?i)usage limit"), "--limit");
        LaunchResult r = launcher().run(agent, "p", "ask", null, null);

        assertEquals(Outcome.RATE_LIMITED, r.outcome());
        assertTrue(r.stderrTail().toLowerCase().contains("usage limit"));
    }

    @Test
    void ansiCodesAreStripped() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 200_000, List.of(), "--ansi");
        LaunchResult r = launcher().run(agent, "color", "ask", null, null);

        assertEquals(Outcome.DONE, r.outcome());
        assertFalse(r.output().contains(String.valueOf((char) 27)), "output must not contain ESC");
        assertTrue(r.output().contains("PROMPT:color"));
    }

    @Test
    void oversizedOutputIsTruncated() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 20, List.of(), "--bytes", "1000");
        LaunchResult r = launcher().run(agent, "p", "ask", null, null);

        assertEquals(Outcome.DONE, r.outcome());
        assertTrue(r.truncated());
        assertTrue(r.output().startsWith("x".repeat(20)));
        assertTrue(r.output().contains("truncated"));
    }

    @Test
    void observerReceivesProcessAndOutput() {
        AgentConfig agent = fake(PromptVia.STDIN, 30, 200_000, List.of());
        AtomicReference<Process> started = new AtomicReference<>();
        StringBuilder seen = new StringBuilder();
        LaunchObserver observer = new LaunchObserver() {
            @Override
            public void onStart(Process process) {
                started.set(process);
            }

            @Override
            public synchronized void onStdout(String chunk) {
                seen.append(chunk);
            }
        };

        LaunchResult r = launcher().run(agent, "watch me", "ask", null, observer);

        assertEquals(Outcome.DONE, r.outcome());
        assertNotNull(started.get(), "onStart should have received the process");
        assertTrue(seen.toString().contains("PROMPT:watch me"));
    }

    @Test
    void ptyModeCapturesOutput() {
        // Run FakeAgent under a real pseudo-terminal (pty4j). Output must still be captured and
        // rendered clean. The prompt is passed as an argument (PTY mode does not use stdin).
        List<String> baseArgs = new ArrayList<>();
        baseArgs.add("-cp");
        baseArgs.add(System.getProperty("java.class.path"));
        baseArgs.add("com.picamigos.test.FakeAgent");
        baseArgs.add("--from-arg");
        AgentConfig agent = new AgentConfig(
                "Pty Fake", "java", "fake", List.of(), baseArgs, PromptVia.ARG,
                Map.of("ask", List.of()), Map.of(), "none", List.of(),
                30, 200_000, true, true); // enabled, pty

        LaunchResult r = launcher().run(agent, "pty-prompt-xyz", "ask", null, null);

        assertEquals(Outcome.DONE, r.outcome());
        assertTrue(r.output().contains("FAKE_OK"), () -> "output: " + r.output());
        assertTrue(r.output().contains("PROMPT:pty-prompt-xyz"), () -> "output: " + r.output());
    }

    @Test
    void missingExecutableThrows() {
        AgentConfig agent = new AgentConfig(
                "Ghost", "definitely-not-a-real-binary-xyz", "ghost",
                List.of(), List.of(), PromptVia.STDIN,
                Map.of(), Map.of(), "none", List.of(), 30, 200_000, true, false);

        assertThrows(AgentLaunchException.class,
                () -> launcher().run(agent, "p", "ask", null, null));
    }
}

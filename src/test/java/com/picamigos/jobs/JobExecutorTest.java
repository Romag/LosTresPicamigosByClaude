package com.picamigos.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import com.picamigos.config.AgentsConfig;
import com.picamigos.exec.AgentLauncher;
import com.picamigos.test.FakeAgents;
import com.picamigos.usage.UsageLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class JobExecutorTest {

    private JobRegistry registry;
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        AgentsConfig config = FakeAgents.config();
        registry = new JobRegistry();
        UsageLedger ledger = new UsageLedger(config.windowHours(), Clock.systemUTC());
        // Use a stable (non-temp) working dir: these tests kill processes, and a killed child's cwd
        // handle can briefly linger on Windows, which would make @TempDir cleanup fail.
        executor = new JobExecutor(config, new AgentLauncher(Path.of(".").toAbsolutePath()), registry, ledger, 4);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void twoConcurrentJobsBothComplete() throws Exception {
        Job a = executor.start("ok", "first", "ask", null);
        Job b = executor.start("ok", "second", "ask", null);

        assertTrue(a.awaitCompletion(Duration.ofSeconds(20)));
        assertTrue(b.awaitCompletion(Duration.ofSeconds(20)));

        assertEquals(JobStatus.DONE, a.status());
        assertEquals(JobStatus.DONE, b.status());
        assertTrue(a.view(200_000).output().contains("PROMPT:first"));
        assertTrue(b.view(200_000).output().contains("PROMPT:second"));
    }

    @Test
    void partialOutputVisibleWhileRunning() throws Exception {
        Job job = executor.start("slow", "streaming", "ask", null);

        // The fake prints its body, then sleeps 3s; we should observe partial output while RUNNING.
        boolean sawPartialWhileRunning = false;
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            JobView v = job.view(200_000);
            if (v.status() == JobStatus.RUNNING && v.output().contains("FAKE_OK")) {
                sawPartialWhileRunning = true;
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(sawPartialWhileRunning, "expected partial output while job was still RUNNING");
        assertTrue(job.awaitCompletion(Duration.ofSeconds(20)));
        assertEquals(JobStatus.DONE, job.status());
    }

    @Test
    void cancelRunningJob() throws Exception {
        Job job = executor.start("veryslow", "p", "ask", null);

        // Wait until the process is up and has produced its initial output.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !job.view(200_000).output().contains("FAKE_OK")) {
            Thread.sleep(50);
        }
        assertTrue(executor.cancel(job.id()));

        assertTrue(job.awaitCompletion(Duration.ofSeconds(10)));
        assertEquals(JobStatus.CANCELLED, job.status());
    }

    @Test
    void rateLimitedJob() {
        Job job = executor.startAndWait("limit", "p", "ask", null);
        assertEquals(JobStatus.RATE_LIMITED, job.status());
    }

    @Test
    void failedToStartIsFailed() {
        Job job = executor.startAndWait("ghost", "p", "ask", null);
        assertEquals(JobStatus.FAILED, job.status());
        assertTrue(job.view(200_000).output().toLowerCase().contains("not found"));
    }

    @Test
    void unknownAgentThrows() {
        assertThrows(IllegalArgumentException.class, () -> executor.start("nope", "p", "ask", null));
    }

    @Test
    void registryListAndGet() throws Exception {
        Job job = executor.startAndWait("ok", "p", "ask", null);
        assertNotNull(registry.get(job.id()).orElse(null));
        assertTrue(registry.list(null, null, 200_000).stream().anyMatch(v -> v.id().equals(job.id())));
        assertTrue(registry.list("ok", JobStatus.DONE, 200_000).stream().anyMatch(v -> v.id().equals(job.id())));
    }
}

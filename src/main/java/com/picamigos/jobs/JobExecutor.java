package com.picamigos.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.AgentsConfig;
import com.picamigos.exec.AgentLaunchException;
import com.picamigos.exec.AgentLauncher;
import com.picamigos.exec.LaunchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs delegation jobs on a bounded thread pool. Each job spawns one agent CLI via {@link AgentLauncher}
 * and updates its {@link Job} as it progresses and completes.
 *
 * <p>Provides both async ({@link #start}) and blocking ({@link #startAndWait}) entry points, plus
 * {@link #cancel}. Bounding the pool limits how many agent subprocesses run at once.
 */
public final class JobExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    /** Extra time to wait, on top of an agent's own timeout, before a blocking call gives up. */
    private static final Duration WAIT_SLACK = Duration.ofSeconds(30);

    private final AgentsConfig config;
    private final AgentLauncher launcher;
    private final JobRegistry registry;
    private final ExecutorService pool;

    public JobExecutor(AgentsConfig config, AgentLauncher launcher, JobRegistry registry, int maxConcurrent) {
        this.config = Objects.requireNonNull(config);
        this.launcher = Objects.requireNonNull(launcher);
        this.registry = Objects.requireNonNull(registry);
        this.pool = Executors.newFixedThreadPool(Math.max(1, maxConcurrent), namedThreadFactory());
    }

    /**
     * Starts a job asynchronously and returns it immediately (status RUNNING).
     *
     * @throws IllegalArgumentException if the agent is unknown
     */
    public Job start(String agentSpec, String prompt, String mode, Integer timeoutOverride) {
        AgentConfig agent = config.find(agentSpec)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentSpec));
        String canonical = config.resolveName(agentSpec).orElse(agentSpec);
        String runMode = (mode == null || mode.isBlank()) ? "ask" : mode;

        Job job = registry.create(canonical, runMode, prompt);
        pool.execute(() -> runJob(job, agent, prompt, runMode, timeoutOverride));
        return job;
    }

    /**
     * Starts a job and blocks until it finishes (or a generous safety timeout elapses). The agent's own
     * timeout bounds the run; the returned job is terminal in the normal case.
     */
    public Job startAndWait(String agentSpec, String prompt, String mode, Integer timeoutOverride) {
        AgentConfig agent = config.find(agentSpec)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentSpec));
        int timeoutSeconds = (timeoutOverride != null && timeoutOverride > 0)
                ? timeoutOverride : agent.timeoutSeconds();
        Job job = start(agentSpec, prompt, mode, timeoutOverride);
        try {
            job.awaitCompletion(Duration.ofSeconds(timeoutSeconds).plus(WAIT_SLACK));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return job;
    }

    /** Requests cancellation of a running job; returns true if the job exists and was running. */
    public boolean cancel(String jobId) {
        return registry.get(jobId)
                .filter(j -> !j.status().isTerminal())
                .map(j -> {
                    j.requestCancel();
                    return true;
                })
                .orElse(false);
    }

    private void runJob(Job job, AgentConfig agent, String prompt, String mode, Integer timeoutOverride) {
        try {
            LaunchResult result = launcher.run(agent, prompt, mode, timeoutOverride, job.observer());
            job.complete(result);
        } catch (AgentLaunchException e) {
            log.warn("Job {} could not start: {}", job.id(), e.getMessage());
            job.fail(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Job {} failed unexpectedly", job.id(), e);
            job.fail("Unexpected error: " + e);
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicLong n = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "job-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

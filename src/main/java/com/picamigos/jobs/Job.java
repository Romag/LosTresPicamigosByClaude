package com.picamigos.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.picamigos.exec.LaunchObserver;
import com.picamigos.exec.LaunchResult;
import com.picamigos.exec.Usage;
import com.picamigos.util.OutputTruncator;

/**
 * A single delegation: one run of an agent CLI. Thread-safe — the executor thread completes it while
 * MCP tool threads read snapshots, and a pump thread streams partial output via {@link #observer()}.
 */
public final class Job {

    /** Safety cap on retained partial output while a job runs. */
    private static final int PARTIAL_CAP = 2_000_000;

    private final String id;
    private final String agent;
    private final String mode;
    private final String prompt;
    private final Instant startedAt;
    private final CountDownLatch done = new CountDownLatch(1);
    private final StringBuilder partialOut = new StringBuilder();
    private final AtomicReference<Process> process = new AtomicReference<>();

    private volatile JobStatus status = JobStatus.RUNNING;
    private volatile Instant lastActivityAt;
    private volatile Instant finishedAt;
    private volatile Integer exitCode;
    private volatile LaunchResult result;
    private volatile Usage usage;
    private volatile String failureMessage;
    private volatile boolean cancelRequested;

    Job(String id, String agent, String mode, String prompt) {
        this.id = id;
        this.agent = agent;
        this.mode = mode;
        this.prompt = prompt;
        this.startedAt = Instant.now();
        this.lastActivityAt = this.startedAt;
    }

    public String id() {
        return id;
    }

    public String agent() {
        return agent;
    }

    public JobStatus status() {
        return status;
    }

    /** A launcher observer that captures the process handle and streams partial output into this job. */
    public LaunchObserver observer() {
        return new LaunchObserver() {
            @Override
            public void onStart(Process p) {
                process.set(p);
                touch();
            }

            @Override
            public void onStdout(String chunk) {
                synchronized (partialOut) {
                    if (partialOut.length() < PARTIAL_CAP) {
                        partialOut.append(chunk);
                    }
                }
                touch();
            }

            @Override
            public void onStderr(String chunk) {
                touch();
            }
        };
    }

    private void touch() {
        lastActivityAt = Instant.now();
    }

    /** Marks the job complete from a launcher result (or CANCELLED if cancellation was requested). */
    void complete(LaunchResult launchResult) {
        synchronized (this) {
            this.result = launchResult;
            this.exitCode = launchResult.exitCode();
            this.usage = launchResult.usage();
            this.finishedAt = Instant.now();
            this.status = cancelRequested ? JobStatus.CANCELLED : JobStatus.fromOutcome(launchResult.outcome());
        }
        done.countDown();
    }

    /** Marks the job failed before/without a launcher result (e.g. the executable could not be started). */
    void fail(String message) {
        synchronized (this) {
            this.failureMessage = message;
            this.finishedAt = Instant.now();
            this.status = cancelRequested ? JobStatus.CANCELLED : JobStatus.FAILED;
        }
        done.countDown();
    }

    /** Requests cancellation: force-kills the running process if any. */
    void requestCancel() {
        cancelRequested = true;
        Process p = process.get();
        if (p != null) {
            p.destroyForcibly();
        }
    }

    /** Waits up to {@code timeout} for the job to reach a terminal state. */
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Builds an immutable snapshot, with output capped to {@code maxOutputChars}. */
    public JobView view(int maxOutputChars) {
        JobStatus st = status;
        Instant fin = finishedAt;
        String rawOutput;
        if (result != null) {
            rawOutput = result.output();
        } else if (failureMessage != null) {
            rawOutput = failureMessage;
        } else {
            synchronized (partialOut) {
                rawOutput = partialOut.toString();
            }
        }
        OutputTruncator.Result truncated = OutputTruncator.truncate(rawOutput, maxOutputChars);
        Instant end = fin != null ? fin : Instant.now();
        long durationMillis = Duration.between(startedAt, end).toMillis();
        return new JobView(id, agent, mode, st, truncated.text(), truncated.truncated(),
                exitCode, startedAt, lastActivityAt, fin, durationMillis);
    }

    /** The final launcher result, or null if the job has not produced one (still running / failed to start). */
    public LaunchResult result() {
        return result;
    }
}

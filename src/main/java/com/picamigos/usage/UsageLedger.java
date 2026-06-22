package com.picamigos.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.picamigos.jobs.JobStatus;

/**
 * Self-managed budget tracker. Since no CLI reliably reports remaining 5-hour budget in headless mode,
 * this records the jobs <em>we</em> delegated within a rolling window per agent, and flips an agent to
 * {@link UsageState#RATE_LIMITED} when a run's outcome was {@code RATE_LIMITED} (detected by the launcher
 * via configured limit patterns), estimating availability as the hit time plus one window.
 *
 * <p>Thread-safe: {@link #record} is called from job-worker threads, {@link #status} from MCP threads.
 */
public final class UsageLedger {

    private record Entry(Instant startedAt, long activeMillis, JobStatus status, Double costUsd) {
    }

    private static final class AgentLog {
        final Deque<Entry> entries = new ArrayDeque<>();
        Instant rateLimitedUntil;
        Instant lastActivityAt;
    }

    private final Map<String, AgentLog> logs = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    public UsageLedger(int windowHours, Clock clock) {
        this.window = Duration.ofHours(Math.max(1, windowHours));
        this.clock = clock;
    }

    /** Records a completed job's usage. Should be called once per terminal job. */
    public void record(String agent, Instant startedAt, long activeMillis, JobStatus status, Double costUsd) {
        AgentLog log = logs.computeIfAbsent(agent, k -> new AgentLog());
        synchronized (log) {
            log.entries.addLast(new Entry(startedAt, activeMillis, status, costUsd));
            Instant end = startedAt.plusMillis(activeMillis);
            if (log.lastActivityAt == null || end.isAfter(log.lastActivityAt)) {
                log.lastActivityAt = end;
            }
            if (status == JobStatus.RATE_LIMITED) {
                log.rateLimitedUntil = clock.instant().plus(window);
            }
        }
    }

    /** Computes the current usage snapshot for an agent (pruning entries outside the window). */
    public AgentUsage status(String agent) {
        AgentLog log = logs.get(agent);
        Instant now = clock.instant();
        if (log == null) {
            return new AgentUsage(agent, UsageState.OK, 0, 0L, null, null, null);
        }
        synchronized (log) {
            Instant cutoff = now.minus(window);
            log.entries.removeIf(e -> e.startedAt().isBefore(cutoff));

            int jobs = log.entries.size();
            long activeSeconds = 0;
            double cost = 0;
            boolean anyCost = false;
            for (Entry e : log.entries) {
                activeSeconds += e.activeMillis() / 1000;
                if (e.costUsd() != null) {
                    cost += e.costUsd();
                    anyCost = true;
                }
            }

            UsageState state = UsageState.OK;
            Instant estimatedAvailableAt = null;
            if (log.rateLimitedUntil != null) {
                if (now.isBefore(log.rateLimitedUntil)) {
                    state = UsageState.RATE_LIMITED;
                    estimatedAvailableAt = log.rateLimitedUntil;
                } else {
                    log.rateLimitedUntil = null; // window refreshed
                }
            }

            return new AgentUsage(agent, state, jobs, activeSeconds,
                    anyCost ? cost : null, estimatedAvailableAt, log.lastActivityAt);
        }
    }

    /** Convenience: whether the agent is currently rate-limited. */
    public boolean isRateLimited(String agent) {
        return status(agent).rateLimited();
    }
}

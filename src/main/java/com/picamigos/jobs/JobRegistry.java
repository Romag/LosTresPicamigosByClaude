package com.picamigos.jobs;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory store of all jobs for this server session. Thread-safe. */
public final class JobRegistry {

    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /** Creates and registers a new RUNNING job. */
    public Job create(String agent, String mode, String prompt) {
        String id = "job-" + counter.incrementAndGet();
        Job job = new Job(id, agent, mode, prompt);
        jobs.put(id, job);
        return job;
    }

    public Optional<Job> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /**
     * Lists job snapshots, optionally filtered by agent and/or status, newest-first.
     *
     * @param agentFilter  canonical agent name, or null for any
     * @param statusFilter status, or null for any
     * @param maxOutputChars cap on each snapshot's output length
     */
    public List<JobView> list(String agentFilter, JobStatus statusFilter, int maxOutputChars) {
        return jobs.values().stream()
                .filter(j -> agentFilter == null || agentFilter.equals(j.agent()))
                .filter(j -> statusFilter == null || statusFilter == j.status())
                .map(j -> j.view(maxOutputChars))
                .sorted(Comparator.comparing(JobView::startedAt).reversed())
                .toList();
    }
}

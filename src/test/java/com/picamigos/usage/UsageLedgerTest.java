package com.picamigos.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.picamigos.jobs.JobStatus;
import org.junit.jupiter.api.Test;

class UsageLedgerTest {

    /** A clock whose instant can be advanced, for testing time-based window behavior. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void recordsActiveTimeAndJobCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-22T10:00:00Z"));
        UsageLedger ledger = new UsageLedger(5, clock);

        ledger.record("codex", clock.instant(), 30_000, JobStatus.DONE, null);
        ledger.record("codex", clock.instant(), 90_000, JobStatus.DONE, null);

        AgentUsage usage = ledger.status("codex");
        assertEquals(UsageState.OK, usage.state());
        assertEquals(2, usage.windowJobs());
        assertEquals(120, usage.windowActiveSeconds());
    }

    @Test
    void unknownAgentIsOkAndEmpty() {
        UsageLedger ledger = new UsageLedger(5, Clock.systemUTC());
        AgentUsage usage = ledger.status("nobody");
        assertEquals(UsageState.OK, usage.state());
        assertEquals(0, usage.windowJobs());
        assertNull(usage.estimatedAvailableAt());
    }

    @Test
    void rateLimitIsSetThenClearsAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-22T10:00:00Z"));
        UsageLedger ledger = new UsageLedger(5, clock);

        ledger.record("claude", clock.instant(), 1_000, JobStatus.RATE_LIMITED, null);

        AgentUsage limited = ledger.status("claude");
        assertEquals(UsageState.RATE_LIMITED, limited.state());
        assertEquals(Instant.parse("2026-06-22T15:00:00Z"), limited.estimatedAvailableAt());

        clock.advance(Duration.ofHours(5).plusMinutes(1));
        AgentUsage recovered = ledger.status("claude");
        assertEquals(UsageState.OK, recovered.state());
        assertNull(recovered.estimatedAvailableAt());
    }

    @Test
    void entriesRollOffAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-22T10:00:00Z"));
        UsageLedger ledger = new UsageLedger(5, clock);

        ledger.record("codex", clock.instant(), 60_000, JobStatus.DONE, null);
        assertEquals(1, ledger.status("codex").windowJobs());

        clock.advance(Duration.ofHours(6));
        assertEquals(0, ledger.status("codex").windowJobs());
    }

    @Test
    void aggregatesCostWhenPresent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-22T10:00:00Z"));
        UsageLedger ledger = new UsageLedger(5, clock);

        ledger.record("claude", clock.instant(), 1_000, JobStatus.DONE, 0.02);
        ledger.record("claude", clock.instant(), 1_000, JobStatus.DONE, 0.03);

        AgentUsage usage = ledger.status("claude");
        assertTrue(Math.abs(usage.estWindowCostUsd() - 0.05) < 1e-9);
    }
}

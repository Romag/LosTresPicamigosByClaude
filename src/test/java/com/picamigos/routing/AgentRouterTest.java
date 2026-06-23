package com.picamigos.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.AgentsConfig;
import com.picamigos.config.PromptVia;
import com.picamigos.jobs.JobStatus;
import com.picamigos.usage.UsageLedger;
import org.junit.jupiter.api.Test;

class AgentRouterTest {

    /** A config backed by an installed executable ("java") with the given capability scores. */
    private static AgentConfig agent(int implement, int review, String executable) {
        return agent(implement, review, executable, true);
    }

    private static AgentConfig agent(int implement, int review, String executable, boolean enabled) {
        return new AgentConfig(
                "Agent-" + executable, executable, "m",
                List.of(), List.of(), PromptVia.STDIN,
                Map.of(), Map.of("implement", implement, "review", review),
                "none", List.of(), 30, 200_000, enabled);
    }

    private static AgentsConfig config(Map<String, AgentConfig> agents) {
        return new AgentsConfig(5, agents);
    }

    private static List<String> names(List<AgentRecommendation> recs) {
        return recs.stream().map(AgentRecommendation::agent).toList();
    }

    @Test
    void ranksByCapabilityDescending() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java"));
        agents.put("bravo", agent(5, 8, "java"));
        agents.put("charlie", agent(7, 6, "java"));
        AgentRouter router = new AgentRouter(config(agents), new UsageLedger(5, Clock.systemUTC()));

        assertEquals(List.of("alpha", "charlie", "bravo"), names(router.recommend("implement", 3)));
    }

    @Test
    void excludesUninstalledAgents() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java"));
        agents.put("ghost", agent(10, 10, "definitely-not-a-real-binary-xyz"));
        AgentRouter router = new AgentRouter(config(agents), new UsageLedger(5, Clock.systemUTC()));

        List<AgentRecommendation> recs = router.recommend("implement", 5);
        assertFalse(names(recs).contains("ghost"));
        assertEquals(List.of("alpha"), names(recs));
    }

    @Test
    void excludesDisabledAgents() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java", true));
        agents.put("bravo", agent(10, 9, "java", false)); // higher score but disabled
        AgentRouter router = new AgentRouter(config(agents), new UsageLedger(5, Clock.systemUTC()));

        assertEquals(List.of("alpha"), names(router.recommend("implement", 5)));
    }

    @Test
    void excludesRateLimitedAgents() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java"));
        agents.put("bravo", agent(5, 8, "java"));
        UsageLedger ledger = new UsageLedger(5, Clock.systemUTC());
        ledger.record("alpha", Instant.now(), 1_000, JobStatus.RATE_LIMITED, null);
        AgentRouter router = new AgentRouter(config(agents), ledger);

        assertEquals(List.of("bravo"), names(router.recommend("implement", 5)));
    }

    @Test
    void breaksTiesByMostRemainingBudget() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(8, 5, "java"));
        agents.put("bravo", agent(8, 5, "java"));
        UsageLedger ledger = new UsageLedger(5, Clock.systemUTC());
        // alpha has used 100s in the window; bravo none -> bravo should rank first on the tie.
        ledger.record("alpha", Instant.now(), 100_000, JobStatus.DONE, null);
        AgentRouter router = new AgentRouter(config(agents), ledger);

        assertEquals(List.of("bravo", "alpha"), names(router.recommend("implement", 2)));
    }

    @Test
    void reviewReturnsAllAvailableAgents() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java"));
        agents.put("bravo", agent(5, 8, "java"));
        agents.put("charlie", agent(7, 6, "java"));
        AgentRouter router = new AgentRouter(config(agents), new UsageLedger(5, Clock.systemUTC()));

        // count=1 is ignored for reviews: all three come back, ordered by review score.
        List<AgentRecommendation> recs = router.recommend("review", 1);
        assertEquals(List.of("bravo", "charlie", "alpha"), names(recs));
    }

    @Test
    void recommendOneReturnsTopPick() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();
        agents.put("alpha", agent(9, 5, "java"));
        agents.put("bravo", agent(5, 8, "java"));
        AgentRouter router = new AgentRouter(config(agents), new UsageLedger(5, Clock.systemUTC()));

        assertTrue(router.recommendOne("implement").isPresent());
        assertEquals("alpha", router.recommendOne("implement").get().agent());
    }
}

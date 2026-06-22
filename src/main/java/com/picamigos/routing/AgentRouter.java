package com.picamigos.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.AgentsConfig;
import com.picamigos.exec.ExecutableResolver;
import com.picamigos.usage.AgentUsage;
import com.picamigos.usage.UsageLedger;

/**
 * Recommends which agent should handle a task, so delegation is capability-based rather than random.
 *
 * <p>Rules:
 * <ol>
 *   <li>rank agents by their configured capability score for the task type (desc);</li>
 *   <li>exclude agents that are not installed (executable not resolvable);</li>
 *   <li>exclude agents that are currently rate-limited;</li>
 *   <li>break ties by most remaining budget (least active time in the window);</li>
 *   <li>for task type {@code review}, return ALL available agents (fan out reviews to everyone).</li>
 * </ol>
 */
public final class AgentRouter {

    private static final String REVIEW = "review";

    private final AgentsConfig config;
    private final UsageLedger ledger;

    public AgentRouter(AgentsConfig config, UsageLedger ledger) {
        this.config = Objects.requireNonNull(config);
        this.ledger = Objects.requireNonNull(ledger);
    }

    private record Candidate(String name, int score, long activeSeconds) {
    }

    /**
     * Recommends up to {@code count} agents for {@code taskType}. For {@code review}, returns all
     * currently-available agents regardless of {@code count}.
     */
    public List<AgentRecommendation> recommend(String taskType, int count) {
        boolean review = REVIEW.equalsIgnoreCase(taskType);

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, AgentConfig> entry : config.agents().entrySet()) {
            String name = entry.getKey();
            AgentConfig cfg = entry.getValue();
            if (ExecutableResolver.resolve(cfg.executable()).isEmpty()) {
                continue; // not installed on this machine
            }
            AgentUsage usage = ledger.status(name);
            if (usage.rateLimited()) {
                continue; // spent — skip until its window refreshes
            }
            candidates.add(new Candidate(name, cfg.capability(taskType), usage.windowActiveSeconds()));
        }

        candidates.sort(Comparator
                .comparingInt((Candidate c) -> c.score()).reversed()
                .thenComparingLong(Candidate::activeSeconds));

        int limit = review ? candidates.size() : Math.max(1, count);
        return candidates.stream()
                .limit(limit)
                .map(c -> new AgentRecommendation(c.name(), c.score(), reason(taskType, c)))
                .toList();
    }

    /** Recommends the single best agent for a task type, if any is available. */
    public Optional<AgentRecommendation> recommendOne(String taskType) {
        return recommend(taskType, 1).stream().findFirst();
    }

    private static String reason(String taskType, Candidate c) {
        return taskType + "=" + c.score() + ", " + c.activeSeconds() + "s used in window";
    }
}

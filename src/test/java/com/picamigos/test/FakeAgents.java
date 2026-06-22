package com.picamigos.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.picamigos.config.AgentConfig;
import com.picamigos.config.AgentsConfig;
import com.picamigos.config.PromptVia;

/**
 * Builds {@link AgentsConfig} / {@link AgentConfig} instances backed by {@link FakeAgent} for tests, so
 * the job and MCP layers can be exercised without the real CLIs or the network.
 */
public final class FakeAgents {

    private FakeAgents() {
    }

    /** Builds an AgentConfig that runs {@link FakeAgent} via the current JVM and classpath. */
    public static AgentConfig agent(String displayName, PromptVia via, int timeoutSeconds,
                                    int maxOutputChars, List<String> limitPatterns, String... controlFlags) {
        List<String> baseArgs = new ArrayList<>();
        baseArgs.add("-cp");
        baseArgs.add(System.getProperty("java.class.path"));
        baseArgs.add("com.picamigos.test.FakeAgent");
        baseArgs.addAll(Arrays.asList(controlFlags));
        return new AgentConfig(
                displayName, "java", "fake",
                List.of(), baseArgs, via,
                Map.of("ask", List.of(), "edit", List.of()),
                Map.of("implement", 5, "review", 5), "none", limitPatterns,
                timeoutSeconds, maxOutputChars);
    }

    /** A config with a handful of fake agents covering the behaviors job tests need. */
    public static AgentsConfig config() {
        List<String> limit = List.of("(?i)usage limit");
        Map<String, AgentConfig> agents = Map.of(
                "ok", agent("OK", PromptVia.STDIN, 30, 200_000, List.of()),
                "slow", agent("Slow", PromptVia.STDIN, 30, 200_000, List.of(), "--sleep", "3000"),
                "veryslow", agent("VerySlow", PromptVia.STDIN, 30, 200_000, List.of(), "--sleep", "20000"),
                "limit", agent("Limited", PromptVia.STDIN, 30, 200_000, limit, "--limit"),
                "ghost", new AgentConfig("Ghost", "definitely-not-a-real-binary-xyz", "ghost",
                        List.of(), List.of(), PromptVia.STDIN, Map.of(), Map.of(), "none", List.of(), 30, 200_000));
        return new AgentsConfig(5, agents);
    }
}

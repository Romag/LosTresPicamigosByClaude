package com.picamigos.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Top-level configuration: the usage window length and the set of known agents.
 *
 * @param windowHours rolling usage-window length in hours (default 5)
 * @param agents      map of canonical agent name -> {@link AgentConfig}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentsConfig(
        int windowHours,
        Map<String, AgentConfig> agents) {

    static final int DEFAULT_WINDOW_HOURS = 5;

    public AgentsConfig {
        if (windowHours <= 0) {
            windowHours = DEFAULT_WINDOW_HOURS;
        }
        agents = agents == null ? Map.of() : Map.copyOf(agents);
    }

    /** Canonical agent names. */
    public Set<String> agentNames() {
        return agents.keySet();
    }

    /**
     * Resolves an agent by its canonical name or any of its aliases (case-insensitive).
     *
     * @return the matching config, or empty if none matches
     */
    public Optional<AgentConfig> find(String nameOrAlias) {
        if (nameOrAlias == null) {
            return Optional.empty();
        }
        AgentConfig direct = agents.get(nameOrAlias);
        if (direct != null) {
            return Optional.of(direct);
        }
        String needle = nameOrAlias.toLowerCase();
        for (Map.Entry<String, AgentConfig> e : agents.entrySet()) {
            if (e.getKey().equalsIgnoreCase(needle)) {
                return Optional.of(e.getValue());
            }
            for (String alias : e.getValue().aliases()) {
                if (alias.equalsIgnoreCase(needle)) {
                    return Optional.of(e.getValue());
                }
            }
        }
        return Optional.empty();
    }

    /** Resolves the canonical name for a name or alias, or empty if unknown. */
    public Optional<String> resolveName(String nameOrAlias) {
        if (nameOrAlias == null) {
            return Optional.empty();
        }
        if (agents.containsKey(nameOrAlias)) {
            return Optional.of(nameOrAlias);
        }
        String needle = nameOrAlias.toLowerCase();
        Map<String, AgentConfig> view = new LinkedHashMap<>(agents);
        for (Map.Entry<String, AgentConfig> e : view.entrySet()) {
            if (e.getKey().equalsIgnoreCase(needle)) {
                return Optional.of(e.getKey());
            }
            for (String alias : e.getValue().aliases()) {
                if (alias.equalsIgnoreCase(needle)) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }
}

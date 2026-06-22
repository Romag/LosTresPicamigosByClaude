package com.picamigos.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration for a single delegated agent (codex, claude, antigravity, ...).
 *
 * <p>Loaded from {@code agents.default.json} and optionally overlaid by a user-provided config file.
 * Exact CLI flags here are best-effort defaults and are meant to be tuned without recompiling.
 *
 * @param displayName    human-readable name shown to orchestrators
 * @param executable     the command to resolve on PATH (e.g. {@code codex}, {@code claude}, {@code agy})
 * @param model          informational model label (e.g. {@code gemini-3.5-flash})
 * @param aliases        alternate names this agent answers to (e.g. {@code gemini}, {@code agy})
 * @param baseArgs       fixed arguments placed before mode args (e.g. {@code ["exec","-","--json"]})
 * @param promptVia      how the prompt is delivered to the process
 * @param modes          per-mode extra args; keys are typically {@code ask} (read-only) and {@code edit}
 * @param capabilities   per-task-type score (higher = better fit); keys like {@code implement}, {@code review}
 * @param usageParse     how to parse usage/cost from output: {@code claude-json}, {@code codex-jsonl}, {@code none}
 * @param limitPatterns  regexes (matched against output) that indicate a usage/rate limit was hit
 * @param timeoutSeconds default per-run timeout in seconds
 * @param maxOutputChars cap on captured output characters before truncation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(
        String displayName,
        String executable,
        String model,
        List<String> aliases,
        List<String> baseArgs,
        PromptVia promptVia,
        Map<String, List<String>> modes,
        Map<String, Integer> capabilities,
        String usageParse,
        List<String> limitPatterns,
        int timeoutSeconds,
        int maxOutputChars) {

    /** Normalizes null collections to empty immutable ones and copies collections defensively. */
    public AgentConfig {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        baseArgs = baseArgs == null ? List.of() : List.copyOf(baseArgs);
        limitPatterns = limitPatterns == null ? List.of() : List.copyOf(limitPatterns);
        modes = modes == null ? Map.of() : Map.copyOf(modes);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
        promptVia = promptVia == null ? PromptVia.STDIN : promptVia;
        usageParse = usageParse == null ? "none" : usageParse;
    }

    /** Extra arguments for the given mode (e.g. {@code ask}/{@code edit}), or empty if unknown. */
    public List<String> modeArgs(String mode) {
        List<String> args = modes.get(mode);
        return args == null ? List.of() : args;
    }

    /** Capability score for the given task type, or 0 if unspecified. */
    public int capability(String taskType) {
        return capabilities.getOrDefault(taskType, 0);
    }
}

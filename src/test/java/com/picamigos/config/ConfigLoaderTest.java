package com.picamigos.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @Test
    void loadsBundledDefaults() {
        AgentsConfig cfg = ConfigLoader.load(null);

        assertEquals(5, cfg.windowHours());
        assertEquals(java.util.Set.of("codex", "claude", "antigravity"), cfg.agentNames());

        AgentConfig codex = cfg.find("codex").orElseThrow();
        assertEquals("codex", codex.executable());
        assertEquals(PromptVia.STDIN, codex.promptVia());
        assertEquals(9, codex.capability("implement"));
        assertEquals(List.of("-s", "workspace-write"), codex.modeArgs("edit"));

        AgentConfig agy = cfg.find("antigravity").orElseThrow();
        assertEquals(PromptVia.ARG, agy.promptVia());
        assertEquals(5, agy.capability("implement"));
    }

    @Test
    void findsByAliasCaseInsensitively() {
        AgentsConfig cfg = ConfigLoader.load(null);

        assertEquals("agy", cfg.find("agy").orElseThrow().executable());
        assertEquals("agy", cfg.find("gemini").orElseThrow().executable());
        assertEquals("codex", cfg.find("CHATGPT").orElseThrow().executable());
        assertEquals("antigravity", cfg.resolveName("gemini").orElseThrow());
        assertTrue(cfg.find("does-not-exist").isEmpty());
    }

    @Test
    void overlayMergesScalarsAndPreservesUntouchedFields(@TempDir Path tmp) throws Exception {
        Path override = tmp.resolve("override.json");
        Files.writeString(override, """
                {
                  "windowHours": 8,
                  "agents": {
                    "codex": { "timeoutSeconds": 120 }
                  }
                }
                """);

        AgentsConfig cfg = ConfigLoader.load(override);

        assertEquals(8, cfg.windowHours());
        AgentConfig codex = cfg.find("codex").orElseThrow();
        assertEquals(120, codex.timeoutSeconds());
        // Untouched fields survive the merge.
        assertEquals(9, codex.capability("implement"));
        assertEquals(200000, codex.maxOutputChars());
        // Other agents untouched.
        assertEquals(900, cfg.find("claude").orElseThrow().timeoutSeconds());
    }

    @Test
    void overlayReplacesArraysWholesale(@TempDir Path tmp) throws Exception {
        Path override = tmp.resolve("override.json");
        Files.writeString(override, """
                { "agents": { "codex": { "limitPatterns": ["(?i)only-this"] } } }
                """);

        AgentsConfig cfg = ConfigLoader.load(override);

        AgentConfig codex = cfg.find("codex").orElseThrow();
        assertEquals(List.of("(?i)only-this"), codex.limitPatterns());
        assertFalse(codex.limitPatterns().contains("(?i)rate.?limit"));
    }
}

package com.picamigos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void parseDefaultOptions() {
        Main.ServerOptions options = Main.ServerOptions.parse(new String[]{});
        assertEquals("127.0.0.1", options.host());
        assertEquals(8765, options.port());
        assertEquals(5, options.windowHours());
        assertFalse(options.dangerouslySkipPermissions());
    }

    @Test
    void parseDangerouslySkipPermissions() {
        Main.ServerOptions options = Main.ServerOptions.parse(new String[]{"--dangerously-skip-permissions"});
        assertTrue(options.dangerouslySkipPermissions());
    }

    @Test
    void parseAllOptions() {
        Main.ServerOptions options = Main.ServerOptions.parse(new String[]{
                "--host", "0.0.0.0",
                "--port", "9000",
                "--repo", "my-repo",
                "--config", "my-config.json",
                "--prompts-file", "my-prompts.json",
                "--window-hours", "10",
                "--dangerously-skip-permissions"
        });
        assertEquals("0.0.0.0", options.host());
        assertEquals(9000, options.port());
        assertEquals(Path.of("my-repo").toAbsolutePath(), options.repo());
        assertEquals(Path.of("my-config.json").toAbsolutePath(), options.config());
        assertEquals(Path.of("my-prompts.json").toAbsolutePath(), options.promptsFile());
        assertEquals(10, options.windowHours());
        assertTrue(options.dangerouslySkipPermissions());
    }

    @Test
    void parseUnknownOptionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                Main.ServerOptions.parse(new String[]{"--unknown-flag"})
        );
    }
}

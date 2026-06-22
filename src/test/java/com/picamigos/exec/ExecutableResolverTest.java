package com.picamigos.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the package-private core of {@link ExecutableResolver}.
 *
 * <p>All tests go through the {@code resolve(command, windows, pathDirs, pathExts)} overload so
 * they are completely independent of the host OS — the {@code windows} flag is passed explicitly.
 */
class ExecutableResolverTest {

    // -------------------------------------------------------------------------
    // Unix: plain binary (no extension)
    // -------------------------------------------------------------------------

    @Test
    void unix_plainBinaryResolves(@TempDir Path tmp) throws Exception {
        Path binary = tmp.resolve("codex");
        Files.createFile(binary);

        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("codex", false, List.of(tmp), List.of());

        assertTrue(result.isPresent(), "expected 'codex' to be found");
        assertEquals(binary, result.get().executable());
        assertFalse(result.get().needsCmdWrapper(), "Unix binary must not need cmd wrapper");
    }

    // -------------------------------------------------------------------------
    // Windows: .exe — no wrapper needed
    // -------------------------------------------------------------------------

    @Test
    void windows_exeResolves(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("codex.exe");
        Files.createFile(exe);

        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("codex", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isPresent(), "expected 'codex.exe' to be found");
        assertEquals(exe, result.get().executable());
        assertFalse(result.get().needsCmdWrapper(), ".exe must not need cmd wrapper");
    }

    // -------------------------------------------------------------------------
    // Windows: .cmd — wrapper required
    // -------------------------------------------------------------------------

    @Test
    void windows_cmdNeedsWrapper(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("claude.cmd");
        Files.createFile(script);

        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("claude", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isPresent(), "expected 'claude.cmd' to be found");
        assertEquals(script, result.get().executable());
        assertTrue(result.get().needsCmdWrapper(), ".cmd file must require cmd wrapper");
    }

    // -------------------------------------------------------------------------
    // Not found
    // -------------------------------------------------------------------------

    @Test
    void notFound(@TempDir Path tmp) {
        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("nope", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isEmpty(), "unknown command should return empty");
    }

    @Test
    void notFound_unix(@TempDir Path tmp) {
        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("nope", false, List.of(tmp), List.of());

        assertTrue(result.isEmpty(), "unknown command should return empty on Unix too");
    }

    // -------------------------------------------------------------------------
    // Windows: extension precedence (.EXE before .CMD)
    // -------------------------------------------------------------------------

    @Test
    void windows_extensionPrecedence(@TempDir Path tmp) throws Exception {
        // Both exist; PATHEXT lists .EXE before .CMD, so .exe must win.
        Path exe = tmp.resolve("tool.exe");
        Path cmd = tmp.resolve("tool.cmd");
        Files.createFile(exe);
        Files.createFile(cmd);

        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("tool", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isPresent());
        assertEquals(exe, result.get().executable(), ".exe must be preferred over .cmd");
        assertFalse(result.get().needsCmdWrapper(), ".exe must not need cmd wrapper");
    }

    // -------------------------------------------------------------------------
    // Blank command
    // -------------------------------------------------------------------------

    @Test
    void blankCommandReturnsEmpty(@TempDir Path tmp) {
        assertTrue(ExecutableResolver.resolve("", false, List.of(tmp), List.of()).isEmpty());
        assertTrue(ExecutableResolver.resolve("   ", true, List.of(tmp), List.of(".EXE")).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Windows: .bat — wrapper required
    // -------------------------------------------------------------------------

    @Test
    void windows_batNeedsWrapper(@TempDir Path tmp) throws Exception {
        Path bat = tmp.resolve("setup.bat");
        Files.createFile(bat);

        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("setup", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isPresent(), "expected 'setup.bat' to be found");
        assertEquals(bat, result.get().executable());
        assertTrue(result.get().needsCmdWrapper(), ".bat file must require cmd wrapper");
    }

    // -------------------------------------------------------------------------
    // Empty PATH dirs list
    // -------------------------------------------------------------------------

    @Test
    void emptyPathDirsReturnsEmpty() {
        assertTrue(
                ExecutableResolver.resolve("codex", false, List.of(), List.of()).isEmpty(),
                "empty PATH dirs should return empty");
    }

    // -------------------------------------------------------------------------
    // Windows: command already has extension and matches file
    // -------------------------------------------------------------------------

    @Test
    void windows_commandWithExplicitExtension(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("codex.exe");
        Files.createFile(exe);

        // Pass "codex.exe" (already has extension) — should still resolve.
        Optional<ExecutableResolver.Resolution> result =
                ExecutableResolver.resolve("codex.exe", true, List.of(tmp), List.of(".EXE", ".CMD", ".BAT"));

        assertTrue(result.isPresent());
        assertEquals(exe, result.get().executable());
    }
}

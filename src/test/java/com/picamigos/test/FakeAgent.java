package com.picamigos.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A fake "agent CLI" used by integration tests, so tests never depend on the real codex/claude/agy
 * binaries or the network. Run as: {@code java -cp <cp> com.picamigos.test.FakeAgent <flags...>}.
 *
 * <p>It always drains stdin to EOF (so a launcher that fails to close the child's stdin would hang,
 * surfacing that bug as a test timeout).
 *
 * <p>Control flags:
 * <ul>
 *   <li>{@code --from-arg}      — use the last positional argument as the prompt instead of stdin</li>
 *   <li>{@code --sleep <ms>}    — sleep before producing output (simulate a long run)</li>
 *   <li>{@code --fail <code>}   — exit with the given code</li>
 *   <li>{@code --limit}         — print a "usage limit reached" message to stderr and exit non-zero</li>
 *   <li>{@code --ansi}          — wrap stdout in ANSI color codes</li>
 *   <li>{@code --bytes <n>}     — emit exactly n 'x' characters to stdout (for truncation tests)</li>
 *   <li>{@code --stderr <text>} — print text to stderr</li>
 * </ul>
 */
public final class FakeAgent {

    public static void main(String[] args) throws Exception {
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

        boolean fromArg = false;
        long sleepMs = 0;
        int exitCode = 0;
        boolean limit = false;
        boolean ansi = false;
        int bytes = -1;
        String stderrText = null;
        List<String> positionals = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--from-arg" -> fromArg = true;
                case "--sleep" -> sleepMs = Long.parseLong(args[++i]);
                case "--fail" -> exitCode = Integer.parseInt(args[++i]);
                case "--limit" -> limit = true;
                case "--ansi" -> ansi = true;
                case "--bytes" -> bytes = Integer.parseInt(args[++i]);
                case "--stderr" -> stderrText = args[++i];
                default -> positionals.add(args[i]);
            }
        }

        String prompt = fromArg
                ? (positionals.isEmpty() ? "" : positionals.get(positionals.size() - 1))
                : stdin;

        String esc = String.valueOf((char) 27); // ESC, built without a source-level control char

        // Print output BEFORE sleeping so partial output is observable while the run is in progress.
        if (bytes >= 0) {
            System.out.print("x".repeat(bytes));
        } else {
            String body = "FAKE_OK\nPROMPT:" + prompt.strip();
            if (ansi) {
                body = esc + "[31m" + body + esc + "[0m";
            }
            System.out.println(body);
        }
        System.out.flush();

        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        if (stderrText != null) {
            System.err.println(stderrText);
        }
        if (limit) {
            System.err.println("Usage limit reached - please try again later");
            if (exitCode == 0) {
                exitCode = 1;
            }
        }

        System.out.flush();
        System.err.flush();
        flushAndExit(exitCode);
    }

    private static void flushAndExit(int code) throws IOException {
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    private FakeAgent() {
    }
}

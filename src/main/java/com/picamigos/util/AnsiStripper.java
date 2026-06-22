package com.picamigos.util;

import java.util.regex.Pattern;

/**
 * Removes ANSI escape sequences (colors, cursor moves, OSC title strings) from captured CLI output so
 * the text returned to an orchestrator is clean.
 *
 * <p>Covers the two forms CLIs emit in practice: CSI sequences ({@code ESC [ ... final}, e.g. color
 * codes) and OSC sequences ({@code ESC ] ... BEL}, e.g. window-title updates). The escape (0x1B) and
 * BEL (0x07) bytes are written as ASCII regex hex escapes to keep the source free of control chars.
 */
public final class AnsiStripper {

    private static final Pattern ANSI = Pattern.compile(
            "\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07]*\\x07)");

    private AnsiStripper() {
    }

    /** Returns {@code input} with ANSI escape sequences removed. Null-safe (returns "" for null). */
    public static String strip(String input) {
        if (input == null) {
            return "";
        }
        if (input.isEmpty()) {
            return input;
        }
        return ANSI.matcher(input).replaceAll("");
    }
}

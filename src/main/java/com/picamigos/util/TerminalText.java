package com.picamigos.util;

/**
 * Renders raw terminal output (e.g. from a PTY-driven CLI) into clean plain text.
 *
 * <p>Two passes: strip ANSI escape sequences ({@link AnsiStripper}), then collapse carriage-return
 * line overwrites — a TUI redraws a line by emitting {@code \r} and rewriting it, so the final visible
 * text is what survives after the last {@code \r} at each cursor position.
 */
public final class TerminalText {

    private TerminalText() {
    }

    /** Strips ANSI and resolves {@code \r} overwrites. Null-safe (returns "" for null). */
    public static String render(String input) {
        if (input == null) {
            return "";
        }
        if (input.isEmpty()) {
            return input;
        }
        return collapseCarriageReturns(AnsiStripper.strip(input));
    }

    /** Applies {@code \r} cursor-to-column-0 overwrite semantics, line by line. */
    static String collapseCarriageReturns(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            out.append(renderLine(lines[i]));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String renderLine(String line) {
        if (line.indexOf('\r') < 0) {
            return line;
        }
        StringBuilder buf = new StringBuilder(line.length());
        int cursor = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r') {
                cursor = 0;
            } else if (cursor < buf.length()) {
                buf.setCharAt(cursor++, c);
            } else {
                buf.append(c);
                cursor++;
            }
        }
        return buf.toString();
    }
}

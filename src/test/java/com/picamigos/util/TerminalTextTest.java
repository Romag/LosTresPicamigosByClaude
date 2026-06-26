package com.picamigos.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalTextTest {

    private static final String ESC = String.valueOf((char) 27);

    @Test
    void stripsAnsiAndKeepsText() {
        assertEquals("hello", TerminalText.render(ESC + "[0m" + "hello" + ESC + "[0K"));
    }

    @Test
    void collapsesCarriageReturnOverwrite() {
        // A spinner-style redraw: "I will list" then \r then the full final line.
        String raw = "I will list\rI will list the permission grants";
        assertEquals("I will list the permission grants", TerminalText.render(raw));
    }

    @Test
    void partialOverwriteKeepsTrailingTail() {
        // "abcdef" then \r then "XY" overwrites first two chars -> "XYcdef".
        assertEquals("XYcdef", TerminalText.render("abcdef\rXY"));
    }

    @Test
    void handlesMultilineAndCrlf() {
        // Trailing CRLF is preserved as a trailing newline.
        assertEquals("line1\nline2\n", TerminalText.render("line1\r\nline2\r\n"));
    }

    @Test
    void plainTextUnchanged() {
        assertEquals("just text 123", TerminalText.render("just text 123"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", TerminalText.render(null));
    }
}

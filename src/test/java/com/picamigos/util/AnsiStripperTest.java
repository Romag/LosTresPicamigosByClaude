package com.picamigos.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AnsiStripperTest {

    private static final String ESC = String.valueOf((char) 27);

    @Test
    void stripsCsiColorCodes() {
        String input = ESC + "[31mred" + ESC + "[0m text";
        assertEquals("red text", AnsiStripper.strip(input));
    }

    @Test
    void stripsOscTitleSequences() {
        String bel = String.valueOf((char) 7);
        String input = ESC + "]0;window title" + bel + "after";
        assertEquals("after", AnsiStripper.strip(input));
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("plain text 123", AnsiStripper.strip("plain text 123"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", AnsiStripper.strip(null));
    }

    @Test
    void resultContainsNoEscapeChar() {
        String stripped = AnsiStripper.strip(ESC + "[1;32mok" + ESC + "[0m");
        assertFalse(stripped.contains(ESC), "stripped output must not contain ESC");
    }
}

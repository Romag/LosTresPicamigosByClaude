package com.picamigos.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutputTruncatorTest {

    @Test
    void shortTextIsNotTruncated() {
        OutputTruncator.Result r = OutputTruncator.truncate("hello", 100);
        assertFalse(r.truncated());
        assertEquals("hello", r.text());
        assertEquals(5, r.originalLength());
    }

    @Test
    void longTextIsTruncatedWithMarker() {
        String input = "x".repeat(100);
        OutputTruncator.Result r = OutputTruncator.truncate(input, 20);
        assertTrue(r.truncated());
        assertEquals(100, r.originalLength());
        assertTrue(r.text().startsWith("x".repeat(20)));
        assertTrue(r.text().contains("truncated"));
    }

    @Test
    void zeroMaxDisablesTruncation() {
        String input = "x".repeat(100);
        OutputTruncator.Result r = OutputTruncator.truncate(input, 0);
        assertFalse(r.truncated());
        assertEquals(input, r.text());
    }

    @Test
    void nullBecomesEmpty() {
        OutputTruncator.Result r = OutputTruncator.truncate(null, 10);
        assertEquals("", r.text());
        assertFalse(r.truncated());
    }
}

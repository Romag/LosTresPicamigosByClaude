package com.picamigos.util;

/**
 * Caps captured output to a maximum number of characters, appending a clear marker when truncation
 * occurs so the orchestrator knows output was cut.
 */
public final class OutputTruncator {

    /**
     * @param text           the (possibly truncated) text to return
     * @param truncated      whether truncation happened
     * @param originalLength the original character length before truncation
     */
    public record Result(String text, boolean truncated, int originalLength) {
    }

    private OutputTruncator() {
    }

    /**
     * Truncates {@code text} to {@code maxChars} characters. A {@code maxChars <= 0} disables
     * truncation. When truncated, a marker noting the original length is appended.
     */
    public static Result truncate(String text, int maxChars) {
        if (text == null) {
            return new Result("", false, 0);
        }
        int len = text.length();
        if (maxChars <= 0 || len <= maxChars) {
            return new Result(text, false, len);
        }
        String marker = "\n... [output truncated: " + len + " chars total, showing first " + maxChars + "]";
        return new Result(text.substring(0, maxChars) + marker, true, len);
    }
}

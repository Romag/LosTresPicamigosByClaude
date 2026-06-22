package com.picamigos.board;

import java.time.Instant;

/**
 * A single comment on the shared board.
 *
 * @param id     unique note id
 * @param thread the thread/topic this note belongs to (e.g. {@code review/PR-3})
 * @param author who wrote it (free text, typically an agent name)
 * @param text   the comment body
 * @param ts     when it was posted
 */
public record Note(String id, String thread, String author, String text, Instant ts) {
}

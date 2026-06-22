package com.picamigos.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A shared, in-memory comment board. Because all agents connect to the same server, notes posted by
 * one agent are visible to the others — this is how cross-agent review comments are exchanged.
 *
 * <p>Notes are grouped by an arbitrary thread key and returned in posting order. Thread-safe.
 */
public final class NoteBoard {

    private final Map<String, List<Note>> threads = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    /** Appends a note to a thread and returns it. */
    public Note post(String thread, String author, String text) {
        Note note = new Note(
                "note-" + counter.incrementAndGet(),
                thread,
                author == null ? "" : author,
                text == null ? "" : text,
                Instant.now());
        threads.computeIfAbsent(thread, k -> new CopyOnWriteArrayList<>()).add(note);
        return note;
    }

    /** Returns the notes in a thread, in posting order (empty if none). */
    public List<Note> get(String thread) {
        return List.copyOf(threads.getOrDefault(thread, List.of()));
    }

    /** Returns the known thread keys. */
    public List<String> threads() {
        return List.copyOf(threads.keySet());
    }
}

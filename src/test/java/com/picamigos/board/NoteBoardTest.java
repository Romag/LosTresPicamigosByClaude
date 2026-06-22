package com.picamigos.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class NoteBoardTest {

    @Test
    void postsAreReturnedInOrder() {
        NoteBoard board = new NoteBoard();
        board.post("review/1", "codex", "first");
        board.post("review/1", "claude", "second");

        List<Note> notes = board.get("review/1");
        assertEquals(2, notes.size());
        assertEquals("first", notes.get(0).text());
        assertEquals("second", notes.get(1).text());
        assertEquals("codex", notes.get(0).author());
    }

    @Test
    void emptyThreadReturnsEmptyList() {
        assertTrue(new NoteBoard().get("nope").isEmpty());
    }

    @Test
    void threadsAreIsolated() {
        NoteBoard board = new NoteBoard();
        board.post("t1", "a", "x");
        board.post("t2", "b", "y");
        assertEquals(1, board.get("t1").size());
        assertEquals(1, board.get("t2").size());
    }

    @Test
    void noteIdsAreUnique() {
        NoteBoard board = new NoteBoard();
        Note a = board.post("t", "a", "x");
        Note b = board.post("t", "a", "y");
        assertNotEquals(a.id(), b.id());
    }
}

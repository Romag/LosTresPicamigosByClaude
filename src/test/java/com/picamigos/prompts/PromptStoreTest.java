package com.picamigos.prompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PromptStoreTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Basic CRUD
    // -----------------------------------------------------------------------

    @Test
    void saveAndGetReturnsPrompt() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        PromptTemplate t = new PromptTemplate("greet", "Hello, {name}!", "Greeting template", "chat");

        store.save(t);

        Optional<PromptTemplate> result = store.get("greet");
        assertTrue(result.isPresent());
        assertEquals(t, result.get());
    }

    @Test
    void getOfUnknownNameIsEmpty() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        assertTrue(store.get("nonexistent").isEmpty());
    }

    @Test
    void listReturnsAllSavedPrompts() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        PromptTemplate a = new PromptTemplate("alpha", "template A", "", "");
        PromptTemplate b = new PromptTemplate("beta", "template B", "", "");

        store.save(a);
        store.save(b);

        List<PromptTemplate> all = store.list();
        assertEquals(2, all.size());
        assertTrue(all.contains(a));
        assertTrue(all.contains(b));
    }

    @Test
    void saveWithExistingNameOverwrites() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        store.save(new PromptTemplate("dup", "original", "", ""));
        store.save(new PromptTemplate("dup", "updated", "", ""));

        Optional<PromptTemplate> result = store.get("dup");
        assertTrue(result.isPresent());
        assertEquals("updated", result.get().template());
        assertEquals(1, store.list().size(), "Upsert must not create a duplicate entry");
    }

    @Test
    void deleteRemovesPromptAndReturnsTrue() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        store.save(new PromptTemplate("bye", "Goodbye, {name}!", "", ""));

        boolean removed = store.delete("bye");

        assertTrue(removed);
        assertTrue(store.get("bye").isEmpty());
    }

    @Test
    void deleteOfUnknownNameReturnsFalse() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        assertFalse(store.delete("ghost"));
    }

    // -----------------------------------------------------------------------
    // Render
    // -----------------------------------------------------------------------

    @Test
    void renderFillsVariables() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        store.save(new PromptTemplate("review", "Review {file} for {kind} issues", "", ""));

        String result = store.render("review", Map.of("file", "App.java", "kind", "security"));

        assertEquals("Review App.java for security issues", result);
    }

    @Test
    void renderLeavesUnprovidedPlaceholdersIntact() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        store.save(new PromptTemplate("partial", "Hello {name}, your token is {token}", "", ""));

        String result = store.render("partial", Map.of("name", "Alice"));

        assertEquals("Hello Alice, your token is {token}", result);
    }

    @Test
    void renderThrowsForUnknownName() {
        PromptStore store = new PromptStore(tempDir.resolve("prompts.json"));
        assertThrows(NoSuchElementException.class,
                () -> store.render("missing", Map.of()));
    }

    // -----------------------------------------------------------------------
    // Persistence across reload
    // -----------------------------------------------------------------------

    @Test
    void persistenceAcrossReload() {
        Path storeFile = tempDir.resolve("store.json");
        PromptTemplate original = new PromptTemplate("persisted", "My template", "desc", "review");

        // Write in a first store instance
        PromptStore first = new PromptStore(storeFile);
        first.save(original);

        // Load from the same file in a brand-new instance
        PromptStore second = new PromptStore(storeFile);
        Optional<PromptTemplate> loaded = second.get("persisted");

        assertTrue(loaded.isPresent());
        assertEquals(original.name(), loaded.get().name());
        assertEquals(original.template(), loaded.get().template());
        assertEquals(original.description(), loaded.get().description());
        assertEquals(original.defaultTaskType(), loaded.get().defaultTaskType());
    }

    // -----------------------------------------------------------------------
    // PromptTemplate validation
    // -----------------------------------------------------------------------

    @Test
    void promptTemplateRejectsNullName() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptTemplate(null, "tmpl", "", ""));
    }

    @Test
    void promptTemplateRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptTemplate("   ", "tmpl", "", ""));
    }

    @Test
    void promptTemplateRejectsNullTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> new PromptTemplate("name", null, "", ""));
    }

    @Test
    void promptTemplateNormalizesNullDescriptionAndTaskType() {
        PromptTemplate t = new PromptTemplate("n", "t", null, null);
        assertEquals("", t.description());
        assertEquals("", t.defaultTaskType());
    }
}

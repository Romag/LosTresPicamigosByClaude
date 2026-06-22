package com.picamigos.prompts;

import com.picamigos.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, file-backed CRUD store for {@link PromptTemplate} instances.
 *
 * <p>Prompts are keyed by their {@link PromptTemplate#name()}. Mutations are persisted to the backing
 * file immediately. The file is only created on the first mutation; if no file exists at construction
 * time the store starts empty.
 *
 * <p>The JSON format is:
 * <pre>{@code
 * {
 *   "prompts": {
 *     "<name>": { "name": "...", "template": "...", "description": "...", "defaultTaskType": "..." },
 *     ...
 *   }
 * }
 * }</pre>
 */
public final class PromptStore {

    /**
     * Private wrapper record that reflects the persisted JSON structure.
     *
     * @param prompts map of template name to {@link PromptTemplate}
     */
    private record Library(Map<String, PromptTemplate> prompts) {}

    private final Path file;
    private final Map<String, PromptTemplate> memory = new ConcurrentHashMap<>();

    /**
     * Constructs a store backed by {@code file}. If the file exists its contents are loaded; otherwise
     * the store starts empty. The file is not created until the first mutation.
     *
     * @param file path of the JSON persistence file
     * @throws UncheckedIOException if the file exists but cannot be read or parsed
     */
    public PromptStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                Library lib = Json.MAPPER.readValue(file.toFile(), Library.class);
                if (lib.prompts() != null) {
                    memory.putAll(lib.prompts());
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load prompt store from " + file, e);
            }
        }
    }

    /**
     * Saves (upserts) a prompt template. Persists the updated store to disk immediately.
     *
     * @param prompt the template to save; must not be null
     * @throws UncheckedIOException if writing fails
     */
    public synchronized void save(PromptTemplate prompt) {
        memory.put(prompt.name(), prompt);
        persist();
    }

    /**
     * Returns the template with the given name, or {@link Optional#empty()} if not found.
     *
     * @param name the template name to look up
     * @return an Optional containing the template, or empty
     */
    public Optional<PromptTemplate> get(String name) {
        return Optional.ofNullable(memory.get(name));
    }

    /**
     * Returns all stored templates sorted by name.
     *
     * @return an immutable, name-sorted list of all templates
     */
    public List<PromptTemplate> list() {
        return memory.values().stream()
                .sorted(Comparator.comparing(PromptTemplate::name))
                .toList();
    }

    /**
     * Deletes the template with the given name.
     *
     * @param name the template name to delete
     * @return {@code true} if a template was removed; {@code false} if no such template existed
     * @throws UncheckedIOException if the deletion causes a write and that write fails
     */
    public synchronized boolean delete(String name) {
        PromptTemplate removed = memory.remove(name);
        if (removed != null) {
            persist();
            return true;
        }
        return false;
    }

    /**
     * Renders the named template with the given variables.
     *
     * @param name      the template name
     * @param variables placeholder substitutions; may be null (treated as empty)
     * @return the rendered string
     * @throws NoSuchElementException if no template with the given name exists
     */
    public String render(String name, Map<String, String> variables) {
        PromptTemplate prompt = memory.get(name);
        if (prompt == null) {
            throw new NoSuchElementException("No prompt template named: " + name);
        }
        return prompt.render(variables);
    }

    /** Writes the current in-memory state to disk, creating parent directories as needed. */
    private void persist() {
        try {
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            // Snapshot the current map into a new Library so we serialize a consistent view
            Library lib = new Library(Map.copyOf(memory));
            Json.MAPPER.writeValue(file.toFile(), lib);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist prompt store to " + file, e);
        }
    }
}

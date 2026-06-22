package com.picamigos.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.picamigos.util.Json;

/**
 * Loads {@link AgentsConfig} from the bundled {@code agents.default.json} resource, optionally
 * overlaid with a user-provided config file.
 *
 * <p>The overlay is a JSON deep-merge: objects are merged recursively, while scalars and arrays in the
 * overlay replace those in the defaults. This lets a user override just a few fields (e.g. an agent's
 * {@code timeoutSeconds}) without restating the whole config.
 */
public final class ConfigLoader {

    /** Classpath resource holding the built-in defaults. */
    public static final String DEFAULT_RESOURCE = "/agents.default.json";

    /** Environment variable that points at an override config file when {@code --config} is absent. */
    public static final String ENV_CONFIG = "PICAMIGOS_CONFIG";

    private ConfigLoader() {
    }

    /**
     * Loads the configuration, applying an override file if one is resolved.
     *
     * @param explicitOverride path passed via {@code --config}, or {@code null}
     * @return the merged, parsed configuration
     */
    public static AgentsConfig load(Path explicitOverride) {
        Path override = resolveOverride(explicitOverride);
        try {
            ObjectNode merged = readDefaults();
            if (override != null) {
                JsonNode overlay = Json.MAPPER.readTree(Files.readString(override));
                deepMerge(merged, overlay);
            }
            return Json.MAPPER.treeToValue(merged, AgentsConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load agent configuration", e);
        }
    }

    /** Resolves the override path from the explicit argument, then the environment variable. */
    static Path resolveOverride(Path explicitOverride) {
        if (explicitOverride != null) {
            return explicitOverride;
        }
        String env = System.getenv(ENV_CONFIG);
        return (env == null || env.isBlank()) ? null : Path.of(env);
    }

    private static ObjectNode readDefaults() throws IOException {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing bundled resource " + DEFAULT_RESOURCE);
            }
            JsonNode node = Json.MAPPER.readTree(in);
            if (!(node instanceof ObjectNode obj)) {
                throw new IOException(DEFAULT_RESOURCE + " is not a JSON object");
            }
            return obj;
        }
    }

    /**
     * Deep-merges {@code overlay} into {@code base} in place. For each field present in {@code overlay}:
     * if both sides are objects, merge recursively; otherwise the overlay value replaces the base value.
     */
    static void deepMerge(ObjectNode base, JsonNode overlay) {
        if (overlay == null || !overlay.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode overlayValue = field.getValue();
            JsonNode baseValue = base.get(name);
            if (baseValue instanceof ObjectNode baseObj && overlayValue.isObject()) {
                deepMerge(baseObj, overlayValue);
            } else {
                base.set(name, overlayValue);
            }
        }
    }
}

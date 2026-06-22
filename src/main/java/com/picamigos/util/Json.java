package com.picamigos.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Shared, pre-configured Jackson {@link ObjectMapper} used across the project.
 *
 * <p>Configured to be lenient on unknown properties (forward-compatible config) and case-insensitive
 * on enums (so {@code "stdin"} maps to {@code PromptVia.STDIN}). The instance is thread-safe once
 * configured, so it is shared.
 */
public final class Json {

    /** Shared, thread-safe mapper. Do not reconfigure after construction. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }
}

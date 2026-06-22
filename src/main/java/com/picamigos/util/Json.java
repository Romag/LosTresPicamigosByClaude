package com.picamigos.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }
}

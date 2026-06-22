package com.picamigos.prompts;

import java.util.Map;

/**
 * An immutable, reusable prompt template that supports {@code {placeholder}} token substitution.
 *
 * <p>Templates are identified by name and can be stored in a {@link PromptStore}. The {@code template}
 * field may contain zero or more {@code {key}} tokens that are replaced at render time.
 *
 * @param name            unique identifier for this template; must not be blank
 * @param template        the raw template text; must not be null
 * @param description     optional human-readable description; normalized to {@code ""} if null
 * @param defaultTaskType optional hint for which task type this template is suited for; normalized to
 *                        {@code ""} if null
 */
public record PromptTemplate(String name, String template, String description, String defaultTaskType) {

    /** Normalizes null description/defaultTaskType to empty string; rejects blank name or null template. */
    public PromptTemplate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        description = description == null ? "" : description;
        defaultTaskType = defaultTaskType == null ? "" : defaultTaskType;
    }

    /**
     * Renders this template by replacing each {@code {key}} token with the corresponding value from
     * {@code variables}.
     *
     * <p>Every occurrence of a provided key is replaced. Tokens with no matching key are left intact so
     * the caller can detect missing variables. A {@code null} map is treated as empty (no replacements).
     *
     * @param variables map of placeholder names to replacement values; may be null
     * @return the rendered string
     */
    public String render(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}

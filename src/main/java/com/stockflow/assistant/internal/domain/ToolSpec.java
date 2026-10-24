package com.stockflow.assistant.internal.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool as the model is told about it.
 *
 * @param inputSchema a JSON Schema object as nested maps and lists. Only {@code type}, {@code description},
 *                    {@code enum}, {@code properties}, {@code required} and {@code additionalProperties: false}: strict
 *                    tool use rejects numeric and length constraints, so ranges are stated in the description and
 *                    checked by the tool. Kept in insertion order — not {@code Map.copyOf}, whose order differs per
 *                    JVM run — because the provider caches the tool list by its exact bytes
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("not a valid tool name: " + name);
        }
    }
}

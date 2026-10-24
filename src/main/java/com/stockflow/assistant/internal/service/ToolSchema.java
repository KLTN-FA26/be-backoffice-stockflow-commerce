package com.stockflow.assistant.internal.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a tool's input schema for strict tool use: an object whose every property is required and that allows no
 * other. Only {@code type}, {@code description} and {@code enum} per property — strict mode rejects
 * {@code minimum}, {@code maxLength} and the like, so each range is stated in the description and checked by
 * {@link ToolArguments}.
 *
 * <p>Insertion-ordered maps throughout, never {@code Map.of}: an immutable map's iteration order changes from one JVM
 * run to the next, and the provider caches the tool list by its exact bytes.</p>
 */
final class ToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    private ToolSchema() {
    }

    static ToolSchema object() {
        return new ToolSchema();
    }

    ToolSchema string(String name, String description) {
        properties.put(name, property("string", description, null));
        return this;
    }

    ToolSchema integer(String name, String description) {
        properties.put(name, property("integer", description, null));
        return this;
    }

    ToolSchema choice(String name, String description, List<String> values) {
        properties.put(name, property("string", description, values));
        return this;
    }

    /** An array of strings; how many is stated in the description, since strict mode takes no array limits. */
    ToolSchema strings(String name, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("description", description);
        property.put("items", Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("type", "string"))));
        properties.put(name, Collections.unmodifiableMap(property));
        return this;
    }

    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
        schema.put("required", List.copyOf(new ArrayList<>(properties.keySet())));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private static Map<String, Object> property(String type, String description, List<String> values) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        if (values != null) {
            property.put("enum", List.copyOf(values));
        }
        return Collections.unmodifiableMap(property);
    }
}

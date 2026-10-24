package com.stockflow.assistant.internal.domain;

import java.util.Objects;

/**
 * A tool the model asked for.
 *
 * @param id        the model's id for this call; the result must quote it
 * @param name      the tool's name
 * @param arguments the arguments as the model wrote them, JSON. Untrusted: the tool validates every field
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}

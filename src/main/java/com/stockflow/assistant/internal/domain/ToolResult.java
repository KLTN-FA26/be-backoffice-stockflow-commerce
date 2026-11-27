package com.stockflow.assistant.internal.domain;

import java.util.Objects;

/**
 * What one tool call answered, as the model receives it.
 *
 * @param callId  the {@link ToolCall#id()} it answers
 * @param content the result, JSON for a success and a short {@code CODE: explanation} line for a failure
 * @param error   true when the call did not produce data; the model is told so and explains it to the user
 */
public record ToolResult(String callId, String content, boolean error) {

    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(content, "content");
    }
}

package com.stockflow.assistant.internal.domain;

import java.util.Objects;

/**
 * One tool call carried out: what was asked, how it ended and what the model was told. Stored in
 * {@code assistant.tool_call}, so what the assistant saw is as auditable as what it answered.
 *
 * @param result     exactly what the model received, at most {@link #MAX_RESULT_LENGTH} characters
 * @param durationMs wall-clock time of the call
 */
public record ToolExecution(ToolCall call, ToolOutcome outcome, String result, long durationMs) {

    /** The longest result a model receives; longer ones are cut and say so. Also {@code ck_tool_call_result}. */
    public static final int MAX_RESULT_LENGTH = 20_000;

    public ToolExecution {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(result, "result");
        if (result.length() > MAX_RESULT_LENGTH) {
            throw new IllegalArgumentException("a tool result is cut to " + MAX_RESULT_LENGTH + " characters first");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }

    /** The result as the model receives it in the next turn. */
    public ToolResult toResult() {
        return new ToolResult(call.id(), result, outcome.isError());
    }
}

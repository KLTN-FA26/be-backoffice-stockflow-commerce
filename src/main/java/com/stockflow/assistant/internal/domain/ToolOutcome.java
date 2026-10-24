package com.stockflow.assistant.internal.domain;

/**
 * How a tool call ended (design §3.2). Stored with every call and checked in {@code assistant.tool_call}.
 *
 * <p>Only {@link #OK} carries data. The others still reach the model, as an error result with a code it can explain:
 * a tool that fails is something to tell the user, not a reason to abandon the answer.</p>
 */
public enum ToolOutcome {
    /** The tool returned data. */
    OK,
    /** The caller lacks the permission, or asked for data outside their scope. */
    DENIED,
    /** The thing asked about does not exist, or not where the caller may see it. */
    NOT_FOUND,
    /** The arguments were invalid, or the request broke a business rule. */
    INVALID,
    /** Anything else; the cause is logged, never sent to the model. */
    FAILED;

    public boolean isError() {
        return this != OK;
    }
}

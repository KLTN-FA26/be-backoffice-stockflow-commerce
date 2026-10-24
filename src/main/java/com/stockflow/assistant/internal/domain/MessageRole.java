package com.stockflow.assistant.internal.domain;

/** Who a stored message is from. Also {@code ck_message_role}. */
public enum MessageRole {
    /** A person's question. */
    USER,
    /** One model turn: an answer, a tool request, or both. */
    ASSISTANT,
    /** What the tools answered; sent to the model, never shown as a message. */
    TOOL_RESULTS;

    public static MessageRole of(Turn turn) {
        return switch (turn) {
            case Turn.Question ignored -> USER;
            case Turn.ModelTurn ignored -> ASSISTANT;
            case Turn.ToolResults ignored -> TOOL_RESULTS;
        };
    }
}

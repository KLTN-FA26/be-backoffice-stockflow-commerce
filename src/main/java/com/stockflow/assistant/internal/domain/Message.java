package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored turn and what came with it.
 *
 * @param text       what a person reads: the question, or the answer; null for tool results and tool-only turns
 * @param model      the model that produced an {@link MessageRole#ASSISTANT} turn; null otherwise
 * @param stopReason why that turn ended; null otherwise
 * @param usage      what that turn cost; {@link LanguageModel.Usage#NONE} otherwise
 * @param executions the tool calls an {@link MessageRole#ASSISTANT} turn asked for, carried out; empty otherwise
 */
public record Message(UUID id, int sequence, MessageRole role, String text, Turn turn, String model,
                      LanguageModel.StopReason stopReason, LanguageModel.Usage usage, List<ToolExecution> executions,
                      Instant createdAt) {

    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(usage, "usage");
        executions = List.copyOf(executions);
        if (role != MessageRole.of(turn)) {
            throw new IllegalArgumentException("role " + role + " does not match the turn");
        }
    }
}

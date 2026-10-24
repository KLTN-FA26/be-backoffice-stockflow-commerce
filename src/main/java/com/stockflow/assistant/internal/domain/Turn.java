package com.stockflow.assistant.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * One turn of a conversation, as the model sees it. A conversation's history is the list of its turns in order, and
 * it is only ever appended to: the model's thinking blocks are valid only when the history is resent unchanged, so no
 * turn is edited, merged or re-serialised after it is stored (design §3.4).
 */
public sealed interface Turn permits Turn.Question, Turn.ModelTurn, Turn.ToolResults {

    /** What the model receives from the user side of the conversation; false for the model's own turns. */
    boolean fromUser();

    /**
     * A question a person asked.
     *
     * @param text    exactly what they typed, trimmed
     * @param context the facts the model needs alongside it and that change per request — the business date, the
     *                asker's roles and warehouses. Kept with the question rather than in the system prompt so the
     *                prompt stays byte-identical and cacheable, and stored so a replay sends the same bytes
     */
    record Question(String text, String context) implements Turn {

        public Question {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(context, "context");
        }

        @Override
        public boolean fromUser() {
            return true;
        }
    }

    /**
     * One reply from the model.
     *
     * @param providerContent the provider's content blocks exactly as received, as JSON. Opaque here: only the
     *                        adapter that produced it reads it back
     * @param text            the reply's text, joined; null when it only called tools
     * @param toolCalls       the tools it asked for, in order; empty when it answered
     */
    record ModelTurn(String providerContent, String text, List<ToolCall> toolCalls) implements Turn {

        public ModelTurn {
            Objects.requireNonNull(providerContent, "providerContent");
            toolCalls = List.copyOf(toolCalls);
        }

        @Override
        public boolean fromUser() {
            return false;
        }
    }

    /** What the tools answered to the {@link ModelTurn} before it, one result per call, in the same order. */
    record ToolResults(List<ToolResult> results) implements Turn {

        public ToolResults {
            results = List.copyOf(results);
            if (results.isEmpty()) {
                throw new IllegalArgumentException("a tool results turn answers at least one call");
            }
        }

        @Override
        public boolean fromUser() {
            return true;
        }
    }
}

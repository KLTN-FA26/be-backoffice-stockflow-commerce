package com.stockflow.assistant.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * The port to the model provider. One call is one model turn: the whole history goes in, one reply comes out.
 *
 * <p>The application layer never sees the provider's types. A reply carries the provider's content as an opaque
 * string ({@link Turn.ModelTurn#providerContent()}) so the adapter can replay it unchanged; everything the loop
 * decides on — the stop reason, the text, the tool calls — is lifted out into plain values. Tests drive the loop with
 * a scripted implementation, so nothing in {@code mvn test} needs an API key or a network.</p>
 */
public interface LanguageModel {

    /**
     * False when the assistant is switched off or has no credentials; the service then refuses before storing the
     * question, rather than storing one that can never be answered.
     */
    boolean available();

    /**
     * One model turn.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ASSISTANT_UNAVAILABLE} when the provider cannot be
     *         reached or refuses the request after its own retries; {@code ASSISTANT_DISABLED} when
     *         {@link #available()} is false
     */
    Reply next(Request request);

    /**
     * A model turn holding only {@code text}, in the provider's own format, for the loop to close an answer the model
     * did not finish (it ran out of tool rounds, was cut off, or declined). Stored and replayed like any other turn,
     * so the history the provider sees next time stays well formed. No call is made.
     */
    Turn.ModelTurn closingTurn(String text);

    /**
     * @param systemPrompt the instructions, byte-identical on every call so the provider can cache them
     * @param tools        sorted by name, for the same reason
     * @param history      the conversation so far, oldest first, ending with a user-side turn
     */
    record Request(String systemPrompt, List<ToolSpec> tools, List<Turn> history) {

        public Request {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            tools = List.copyOf(tools);
            history = List.copyOf(history);
            if (history.isEmpty() || !history.getLast().fromUser()) {
                throw new IllegalArgumentException("a request ends with a question or tool results");
            }
        }
    }

    /**
     * @param model the model that answered, as the provider names it
     */
    record Reply(Turn.ModelTurn turn, StopReason stopReason, Usage usage, String model) {

        public Reply {
            Objects.requireNonNull(turn, "turn");
            Objects.requireNonNull(stopReason, "stopReason");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(model, "model");
        }
    }

    /** Why the model stopped. Only {@link #TOOL_USE} lets the loop run the tools it asked for. */
    enum StopReason {
        /** It answered. */
        END_TURN,
        /** It asked for tools and waits for their results. */
        TOOL_USE,
        /** The output limit cut it off; any tool call in it may be incomplete and is never run. */
        MAX_TOKENS,
        /** The provider's safeguards declined; any tool call in it is never run. */
        REFUSAL,
        /** Anything else the provider reports. */
        OTHER
    }

    /** Tokens of one call, as the provider bills them. */
    record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
                throw new IllegalArgumentException("token counts must not be negative");
            }
        }
    }
}

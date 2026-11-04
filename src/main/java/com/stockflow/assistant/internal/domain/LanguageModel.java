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
     *         reached, times out or fails after the adapter's own retries (worth asking again);
     *         {@code ASSISTANT_QUOTA_EXHAUSTED} when its quota is spent and {@code ASSISTANT_MISCONFIGURED} when it
     *         refuses the key, the model name or the request (neither is worth asking again);
     *         {@code ASSISTANT_DISABLED} when {@link #available()} is false
     */
    Reply next(Request request);

    /**
     * A model turn holding only {@code text}, in the provider's own format, for the loop to close an answer the model
     * did not finish (it ran out of lookups or time, was cut off or declined, or the provider failed). Stored and
     * replayed like any other turn, so the history the provider sees next time stays well formed. No call is made.
     */
    Turn.ModelTurn closingTurn(String text);

    /**
     * @param systemPrompt the instructions, byte-identical on every call so the provider can cache them
     * @param tools        the tools the user may use ({@code ToolAvailability.specsFor}), sorted by name, for the same
     *                     reason: the same grants give the same bytes. The same list on every call of an answer, the
     *                     last one included: the history holds calls to them, which a provider may refuse to read
     *                     without their declarations
     * @param history      the conversation so far, oldest first, ending with a user-side turn — or with the model's
     *                     own answer when a note follows it, asking for that answer to be written again
     * @param toolsAllowed false on the answer's last call: the adapter must not let the model call a tool (Anthropic
     *                     {@code tool_choice: none}, Gemini {@code functionCallingConfig.mode: NONE}). The model then
     *                     answers from the results it already has, instead of asking for a lookup the loop would not
     *                     run and leaving every result already in without an answer
     * @param note         a line from the application, not from the user, that the adapter sends after the history:
     *                     in its last user-side message, or as a new user message after a model turn. Why this call is
     *                     the last, that the previous reply was empty, or which numbers of the answer just written
     *                     nothing holds. Sent with this call only and not stored; null for none
     */
    record Request(String systemPrompt, List<ToolSpec> tools, List<Turn> history, boolean toolsAllowed, String note) {

        public Request {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            tools = List.copyOf(tools);
            history = List.copyOf(history);
            // After the model's own turn only with a note, which every adapter sends as the user's turn that follows:
            // the call asking a model to correct the answer it has just written (AssistantService).
            if (history.isEmpty() || !history.getLast().fromUser() && note == null) {
                throw new IllegalArgumentException("a request ends with a question or tool results, or with a note");
            }
            if (note != null && note.isBlank()) {
                throw new IllegalArgumentException("a note says something, or is null");
            }
        }

        /** An ordinary call: any tool may be called, and there is no note. */
        public Request(String systemPrompt, List<ToolSpec> tools, List<Turn> history) {
            this(systemPrompt, tools, history, true, null);
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

    /**
     * Why the model stopped. Only {@link #TOOL_USE} lets the loop run the tools it asked for.
     *
     * <p>A reply with neither text nor a tool call is empty whatever its stop reason — a function call the provider
     * could not parse, an empty stop after tool results — except a cut-off or declined one, which has its own message.
     * The loop asks again once, then says it could not answer; it never shows such a reply as "no answer found".</p>
     */
    enum StopReason {
        /** It answered: a reply with text. */
        END_TURN,
        /** It asked for tools and waits for their results. */
        TOOL_USE,
        /** The output limit cut it off; any tool call in it may be incomplete and is never run. */
        MAX_TOKENS,
        /** The provider's safeguards declined; any tool call in it is never run. */
        REFUSAL,
        /**
         * Anything else, including a reply with neither text nor a tool call (a malformed function call, an empty end
         * of turn): the model did not answer.
         */
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

package com.stockflow.assistant.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * A turn produced by the loop and not yet stored. The store gives it its id and sequence.
 *
 * @see Message for the meaning of each field
 */
public record PendingMessage(Turn turn, String text, String model, LanguageModel.StopReason stopReason,
                             LanguageModel.Usage usage, List<ToolExecution> executions) {

    /** The model name recorded on a closing turn: written by the application, not produced by a model call. */
    public static final String CLOSING_MODEL = "stockflow";

    public PendingMessage {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(usage, "usage");
        executions = List.copyOf(executions);
        boolean modelTurn = turn instanceof Turn.ModelTurn;
        if (modelTurn != (model != null && stopReason != null)) {
            throw new IllegalArgumentException("exactly the model's turns carry a model and a stop reason");
        }
        if (!modelTurn && !executions.isEmpty()) {
            throw new IllegalArgumentException("only a model turn carries tool executions");
        }
    }

    public static PendingMessage question(Turn.Question question) {
        return new PendingMessage(question, question.text(), null, null, LanguageModel.Usage.NONE, List.of());
    }

    /** The text a person may read is made plain ({@link AnswerText}); the turn sent back to the model is kept whole. */
    public static PendingMessage reply(LanguageModel.Reply reply, List<ToolExecution> executions) {
        return new PendingMessage(reply.turn(), AnswerText.plain(reply.turn().text()), reply.model(),
                reply.stopReason(), reply.usage(), executions);
    }

    public static PendingMessage results(Turn.ToolResults results) {
        return new PendingMessage(results, null, null, null, LanguageModel.Usage.NONE, List.of());
    }

    /**
     * A turn the application writes to end an answer ({@link LanguageModel#closingTurn}): it carries
     * {@link #CLOSING_MODEL} and costs nothing. Its text may begin with what the model wrote before it was stopped,
     * so the text a person reads is made plain like any reply's. One stored right after a question means the provider
     * never replied to it, and the daily cap leaves that question out ({@link ConversationRepository#questionsSince}).
     */
    public static PendingMessage closing(Turn.ModelTurn turn) {
        return new PendingMessage(turn, AnswerText.plain(turn.text()), CLOSING_MODEL, LanguageModel.StopReason.OTHER,
                LanguageModel.Usage.NONE, List.of());
    }

    public MessageRole role() {
        return MessageRole.of(turn);
    }
}

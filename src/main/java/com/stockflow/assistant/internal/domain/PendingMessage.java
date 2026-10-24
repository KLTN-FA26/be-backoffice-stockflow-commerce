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

    public MessageRole role() {
        return MessageRole.of(turn);
    }
}

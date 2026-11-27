package com.stockflow.assistant.internal.domain;

import java.util.Objects;

/**
 * A person's verdict on one answer (design §7): the signal a golden case is drawn from when an answer was wrong, kept
 * in the conversation's own schema and deleted with it.
 *
 * @param reason in their words, stripped; null when they gave none. At most {@value #MAX_REASON} characters, as
 *               {@code ck_answer_feedback_reason} holds
 */
public record AnswerFeedback(FeedbackVerdict verdict, String reason) {

    public static final int MAX_REASON = 500;

    public AnswerFeedback {
        Objects.requireNonNull(verdict, "verdict");
        reason = reason == null || reason.isBlank() ? null : reason.strip();
        if (reason != null && reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("a feedback reason is at most " + MAX_REASON + " characters");
        }
    }
}

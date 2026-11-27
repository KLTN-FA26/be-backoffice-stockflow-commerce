package com.stockflow.assistant.internal.domain;

/** What a person thought of an answer. Also {@code ck_answer_feedback_verdict}. */
public enum FeedbackVerdict {
    /** The answer was right and of use. */
    UP,
    /** It was wrong, refused what it should have answered, or did not help; the reason, when given, says how. */
    DOWN
}

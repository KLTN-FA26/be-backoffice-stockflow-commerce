package com.stockflow.assistant.internal.domain;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Verdicts on answers, one per answer (design §7). Runs inside the caller's transaction, under the OWN scope the
 * endpoint put in force; the caller has established that the conversation is theirs before it writes.
 */
public interface AnswerFeedbackRepository {

    /**
     * Records {@code feedback} on the answer {@code messageId} of {@code conversationId}, replacing a verdict given
     * on it before. Concurrent verdicts on one answer are taken one after the other, the last one standing.
     *
     * @return false, writing nothing, when the message is not an answer of that conversation: not one of its
     *         messages, a question, or a turn that only called tools
     */
    boolean record(UUID conversationId, UUID messageId, UUID ownerUserId, AnswerFeedback feedback);

    /** The verdicts given on {@code messageIds}, by message id; an answer nobody rated is absent. */
    Map<UUID, FeedbackVerdict> verdictsOf(Collection<UUID> messageIds);
}

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
     * on it before, with the rater's roles and warehouses as they are now. Concurrent verdicts on one answer are taken one after the other, the last one standing.
     *
     * @return false, writing nothing, when the message is not an answer of that conversation: not one of its
     *         messages, a question, or a turn that only called tools
     */
    boolean record(UUID conversationId, UUID messageId, UUID ownerUserId, AnswerFeedback feedback,
                   MarkedAnswer.Rater rater);

    /** The verdicts given on {@code messageIds}, by message id; an answer nobody rated is absent. */
    Map<UUID, FeedbackVerdict> verdictsOf(Collection<UUID> messageIds);

    /**
     * Every person's answers given {@code verdict} between {@code from} (included) and {@code to} (excluded), the
     * latest verdict first, with their questions and tool calls: one page, in three queries whatever its size. Not
     * scoped to an owner: for the System Admin's review (contract §5.1), whose endpoint is the guard.
     */
    com.stockflow.common.api.PageResponse<MarkedAnswer> marked(FeedbackVerdict verdict, java.time.Instant from,
                                                               java.time.Instant to, int page, int size);
}

package com.stockflow.assistant.internal.domain;

import com.stockflow.common.api.PageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The conversation store. Every read of a conversation is in the caller's scope (OWN): a user reaches only their own,
 * and another user's id is simply not found.
 *
 * <p>Runs inside the caller's transaction; the application service declares it. The two writes that bracket an
 * answer ({@link #claimAndAsk} and {@link #appendAndRelease}) each expect a transaction of their own, short, and
 * never one around the model calls between them.</p>
 */
public interface ConversationRepository {

    Conversation create(UUID ownerUserId, Instant at);

    /** The conversation, if it is the caller's. */
    Optional<Conversation> findInScope(UUID id);

    /** The caller's conversations, most recently active first. */
    PageResponse<Conversation> listInScope(int page, int size);

    /** Every message of a conversation the caller owns, in order; empty when it is not theirs. */
    List<Message> messagesInScope(UUID conversationId);

    /**
     * Claims the conversation for one question and stores the question, under a row lock on the conversation.
     *
     * @return the conversation's full history, ending with the question
     * @throws com.stockflow.common.error.NotFoundException when it is not the caller's
     * @throws com.stockflow.common.error.BusinessException {@code ASSISTANT_BUSY} while another question in it is
     *         being answered (a claim younger than {@code lease})
     */
    List<Turn> claimAndAsk(UUID conversationId, PendingMessage question, Instant at, Duration lease);

    /**
     * Stores what the loop produced and releases the claim, under the same row lock. Called from a {@code finally}: on
     * a provider failure {@code produced} holds the turns up to it, so tools that already ran are still on record.
     */
    List<Message> appendAndRelease(UUID conversationId, List<PendingMessage> produced, Instant at, Instant claimedAt);

    /** Questions the user asked since {@code since}, across all their conversations: the daily cap. */
    long questionsSince(UUID ownerUserId, Instant since);

    /** Deletes up to {@code limit} conversations idle since before {@code cutoff}, with their messages and calls. */
    int deleteIdleBefore(Instant cutoff, int limit);
}

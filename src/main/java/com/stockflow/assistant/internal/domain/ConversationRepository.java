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
 * <p>Runs inside the caller's transaction; the application service declares it. The writes that bracket an answer
 * ({@link #claimAndAsk}, {@link #appendAndRelease} and, when that fails, {@link #release}) each expect a transaction
 * of their own, short, and never one around the model calls between them.</p>
 */
public interface ConversationRepository {

    Conversation create(UUID ownerUserId, Instant at);

    /** A conversation the assistant's watch opens for {@code ownerUserId} (design §9.1). */
    Conversation createProactive(UUID ownerUserId, Instant at);

    /** How many conversations the watch has opened for {@code ownerUserId} since {@code since}: its daily cap. */
    long proactiveSince(UUID ownerUserId, Instant since);

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
     * Stores what the loop produced and releases the claim, under the same row lock. Called on a failure too: then
     * {@code produced} holds the turns up to it and a closing turn saying what happened, so tools that already ran
     * are still on record.
     */
    /** @param channel the channel the answer's tool runs are recorded on: CHAT for the user's question, PROACTIVE for
     *                 the watch's */
    List<Message> appendAndRelease(UUID conversationId, List<PendingMessage> produced, Instant at, Instant claimedAt,
                                   ToolChannel channel);

    /**
     * Deletes {@code conversationId} if it is a proactive conversation of {@code ownerUserId} with nothing in it: the
     * watch opened it, and its question was refused before it was stored.
     *
     * @return whether it was deleted
     */
    boolean deleteEmptyProactive(UUID conversationId, UUID ownerUserId);

    /**
     * Releases the claim made at {@code claimedAt}, and only that one, without storing anything: for an answer whose
     * {@link #appendAndRelease} failed and took its own release down with it.
     */
    void release(UUID conversationId, Instant claimedAt);

    /**
     * Questions the user asked since {@code since}, across all their conversations: the daily cap. A question whose
     * next stored turn is a closing one ({@link PendingMessage#CLOSING_MODEL}) got no reply from the provider — it
     * failed or refused first — cost nothing, and is not counted. One still being answered has no next turn yet and
     * is counted: leaving it out until it ends would let a user keep several conversations answering past the cap.
     */
    long questionsSince(UUID ownerUserId, Instant since);

    /** Deletes up to {@code limit} conversations idle since before {@code cutoff}, with their messages and calls. */
    int deleteIdleBefore(Instant cutoff, int limit);

    /** Deletes proactive conversations left empty since before {@code cutoff}, at most {@code limit}. */
    int deleteAbandonedProactiveBefore(Instant cutoff, int limit);
}

package com.stockflow.chat.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link ChatMessage}.
 *
 * <p><b>Every read names its {@link Audience}</b> and the adapter filters on it in the query. That is the one place an
 * internal note is kept from a customer (16 BR-07): a controller or a socket handler cannot forget a filter it never
 * applies.</p>
 */
public interface ChatMessageRepository {

    /** Inserts a new message with its files and mentions, or stores what may change of an existing one. */
    ChatMessage save(ChatMessage message);

    Optional<ChatMessage> findById(UUID id, Audience audience);

    /** The message a file belongs to, when {@code audience} may see it. */
    Optional<ChatMessage> findByAttachment(UUID attachmentId, Audience audience);

    /**
     * The message a client sent under {@code clientMessageId} in the conversation, whoever sent it and whatever its
     * visibility: only for the retry check, which compares the sender before saying anything about it.
     */
    Optional<ChatMessage> findByClientMessageId(UUID conversationId, String clientMessageId);

    /** Up to {@code limit} messages before {@code beforeSeq} (all, when null), the newest first. */
    List<ChatMessage> before(UUID conversationId, Audience audience, Long beforeSeq, int limit);

    /** Up to {@code limit} messages after {@code afterSeq}, the oldest first: what a client missed while offline. */
    List<ChatMessage> after(UUID conversationId, Audience audience, long afterSeq, int limit);

    /** The last message of each conversation that {@code audience} sees. */
    Map<UUID, ChatMessage> lastOf(Collection<UUID> conversationIds, Audience audience);

    /** How many messages of each conversation {@code readerId} has not read: seen by them, sent by someone else. */
    Map<UUID, Long> unread(Collection<UUID> conversationIds, UUID readerId, Audience audience);
}

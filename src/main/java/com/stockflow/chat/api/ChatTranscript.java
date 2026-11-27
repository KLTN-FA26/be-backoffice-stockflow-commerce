package com.stockflow.chat.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Part of a conversation as {@link ChatTranscripts} reads it: who it is with, what it is about, and its recent
 * messages, oldest first.
 *
 * <p>Staff are not named: a message says only whether the staff side wrote it and whether the reader did. A reply drawn
 * up from this must not name colleagues to the customer, and a summary needs the roles, not the names.</p>
 *
 * @param status    the chat's status (16 §5), e.g. {@code ACTIVE}, {@code ESCALATED}
 * @param orderId   the order the conversation was opened about; null when none
 * @param contexts  the documents it is about (its contexts, a card's document among them), the primary one first
 * @param truncated older messages exist beyond those returned
 */
public record ChatTranscript(UUID conversationId, UUID customerId, String subject, String status, UUID orderId,
                             List<ChatContextRef> contexts, List<Message> messages, boolean truncated) {

    public ChatTranscript {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * One message.
     *
     * @param sender   {@code CUSTOMER}, {@code AGENT} (any staff member) or {@code SYSTEM}
     * @param kind     {@code TEXT}, {@code ATTACHMENT}, {@code CARD}, {@code SYSTEM} or {@code INTERNAL_NOTE}
     * @param internal seen by the staff alone (an internal note, a staff-only system line)
     * @param byCaller written by the account reading it
     * @param text     what it says, as stored (contacts already masked, 16 BR-08): a card's fallback text, a file's
     *                 caption; null for a deleted message and for a file without a caption
     * @param cardKind the card's kind for a {@code CARD}; null otherwise
     * @param files    how many files it carries
     */
    public record Message(long seq, String sender, String kind, boolean internal, boolean byCaller, String text,
                          ChatCardKind cardKind, int files, Instant sentAt) {
    }
}

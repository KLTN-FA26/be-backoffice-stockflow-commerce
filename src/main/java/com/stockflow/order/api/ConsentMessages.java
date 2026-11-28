package com.stockflow.order.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 17 BR-28): a chat message offered as the customer's consent to a moved delivery window - whose
 * conversation it is and who sent it, so a window is not recorded as agreed on a message the customer never wrote.
 *
 * <p>An interface in {@code order :: api} implemented <b>by the chat module</b>, as payment's {@code InstallmentOrders}
 * is by order: {@code chat} already depends on {@code order :: api}, so order calling the chat module would close a
 * cycle {@code ModularityTest} rejects.</p>
 */
public interface ConsentMessages {

    /**
     * @param customerId   the customer whose conversation the message is in
     * @param orderId      the order the conversation is about; null for a conversation about none
     * @param fromCustomer whether the customer sent it (not Sales, not the system)
     */
    record Message(UUID messageId, UUID customerId, UUID orderId, boolean fromCustomer) {
    }

    Optional<Message> find(UUID messageId);

    /**
     * A message as Sales reads it when choosing the customer's consent, and in the window's history (17 BR-28).
     *
     * @param kind            {@code TEXT}, or {@code ATTACHMENT} (files with an optional caption)
     * @param text            what the customer wrote; null for files without a caption, and for a message taken back
     *                        (deleted: its text is no longer shown to anyone, 16)
     * @param subject         the conversation's subject, when it has one
     */
    record Excerpt(UUID messageId, String kind, String text, String subject, Instant sentAt, boolean deleted) {
    }

    /**
     * The messages the customer sent that {@link #find} would accept as consent for {@code orderId} - in the customer's
     * own conversations about that order or about none - newest first, at most {@code limit}. A message taken back
     * (deleted) is not offered; neither is a card, a system line or an internal note: only what the customer wrote
     * (text) or attached (files).
     */
    List<Excerpt> customerMessages(UUID customerId, UUID orderId, int limit);

    /** The excerpts of the given messages (a deleted one included, without its text), by id; unknown ids are absent. */
    Map<UUID, Excerpt> excerpts(Collection<UUID> messageIds);
}

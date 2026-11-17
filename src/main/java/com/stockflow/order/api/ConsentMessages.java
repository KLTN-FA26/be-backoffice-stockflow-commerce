package com.stockflow.order.api;

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
}

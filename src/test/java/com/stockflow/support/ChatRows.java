package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A chat message as the chat module leaves it (a conversation of the customer, a message in it), written as rows the
 * way {@link ReferenceRows} writes another module's: what a test of something that cites a message needs - the
 * customer's consent to a moved delivery window (kltn-docs 17 BR-28, team plan 4B-8) - without running the chat.
 */
public final class ChatRows {

    private ChatRows() {
    }

    /**
     * A message {@code senderType} ({@code CUSTOMER}, {@code AGENT}, {@code SYSTEM}) sent in a new conversation of
     * {@code customerId}, about {@code orderId} or about no order when it is null. Returns the message.
     */
    public static UUID message(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId, UUID orderId,
                               String senderType) {
        UUID conversation = Identifiers.newId();
        UUID message = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO chat.conversation (id, customer_id, order_id, subject, status, version, created_at)
                    VALUES (?, ?, ?, 'Lich giao', 'OPEN', 0, NOW())""", conversation, customerId, orderId);
            jdbc.update("""
                    INSERT INTO chat.message (id, conversation_id, sender_type, sender_id, body, sent_at, version,
                                              created_at)
                    VALUES (?, ?, ?, NULL, 'Dong y doi lich giao', NOW(), 0, NOW())""", message, conversation,
                    senderType);
        });
        return message;
    }
}

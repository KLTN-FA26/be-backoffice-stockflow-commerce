package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A chat message as the chat module leaves it (a conversation of the customer, a message in it), written as rows the
 * way {@link ReferenceRows} writes another module's: what a test of something that cites a message needs - the
 * customer's consent to a moved delivery window (kltn-docs 17 BR-28, team plan 4B-8) - without running the chat.
 *
 * <p>Since the chat foundation (V20261124000200) a message names the account that sent it and its place in the
 * conversation, and a conversation is born in one of the states of kltn-docs 16 §5: the conversation here waits in the
 * queue, and the sender is a new account of its own.</p>
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
        UUID sender = "SYSTEM".equals(senderType) ? null : Identifiers.newId();
        tx.executeWithoutResult(s -> {
            if (sender != null) {
                jdbc.update("""
                        INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
                        VALUES (?, ?, ?, '!test-no-login', 'ACTIVE', 0, NOW())""", sender, "chat-" + sender,
                        "chat-" + sender + "@example.com");
            }
            jdbc.update("""
                    INSERT INTO chat.conversation (id, customer_id, order_id, subject, status, opened_by_type,
                                                   status_changed_at, version, created_at)
                    VALUES (?, ?, ?, 'Lich giao', 'QUEUED', 'CUSTOMER', NOW(), 0, NOW())""",
                    conversation, customerId, orderId);
            jdbc.update("""
                    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, sent_at, version,
                                              created_at)
                    VALUES (?, ?, 1, ?, ?, 'Dong y doi lich giao', NOW(), 0, NOW())""", message, conversation,
                    senderType, sender);
            jdbc.update("UPDATE chat.conversation SET last_message_seq = 1, last_message_at = NOW() WHERE id = ?",
                    conversation);
        });
        return message;
    }
}

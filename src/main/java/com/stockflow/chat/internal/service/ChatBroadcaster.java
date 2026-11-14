package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Publishes the chat's events to the socket ({@link ChatTopics}), <b>after the transaction commits</b>: a message
 * pushed before its row is committed could be shown and then vanish with a rollback, and a client that fetches the
 * conversation on the push would not find it yet.
 *
 * <p>Sending is best effort. A push that fails is logged and dropped: the message is stored, and a client that missed
 * it catches up with {@code GET .../messages?since=}. Not through Spring Modulith's event registry either, for the same
 * reason: a push retried on the next start would be stale, and every message would leave a row behind.</p>
 *
 * <p>Where a message goes is decided here, once, by its visibility: a customer-visible one to the conversation's topic
 * (and the customer's own queue), a staff-only one to the staff topic alone. Nothing staff-only is ever published where
 * a customer can subscribe (16 BR-07).</p>
 */
@Component
public class ChatBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ChatBroadcaster.class);

    private final ObjectProvider<SimpMessagingTemplate> template;

    /** The template is looked up when the first event goes out: the broker configuration is built after the services. */
    public ChatBroadcaster(ObjectProvider<SimpMessagingTemplate> template) {
        this.template = template;
    }

    /**
     * A message (created or updated) of a conversation of {@code customerUserId}'s customer: to the topic its
     * visibility allows, and a customer-visible one to the customer's own queue as well.
     *
     * @param customerUserIds the customer's portal account(s) to tell on their own queue; empty for none
     */
    public void message(String type, ChatMessageView message, java.util.Collection<UUID> customerUserIds) {
        ChatEvent event = new ChatEvent(type, message.conversationId(), message, message.sentAt());
        if (message.visibility() == Visibility.STAFF) {
            afterCommit(() -> send(ChatTopics.staff(message.conversationId()), event));
            return;
        }
        afterCommit(() -> {
            send(ChatTopics.conversation(message.conversationId()), event);
            customerUserIds.forEach(user -> sendToUser(user, event));
        });
    }

    /** An event both sides of the conversation see (read markers, typing, the conversation's own changes). */
    public void conversation(UUID conversationId, ChatEvent event) {
        afterCommit(() -> send(ChatTopics.conversation(conversationId), event));
    }

    /** An event for the staff's queue. */
    public void inbox(ChatEvent event) {
        afterCommit(() -> send(ChatTopics.INBOX, event));
    }

    /** An event for one person ({@code /user/queue/chat}). */
    public void toUser(UUID userId, ChatEvent event) {
        afterCommit(() -> sendToUser(userId, event));
    }

    public void presence(ChatEvent event) {
        afterCommit(() -> send(ChatTopics.PRESENCE, event));
    }

    private void afterCommit(Runnable push) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    private void send(String destination, ChatEvent event) {
        SimpMessagingTemplate messaging = template.getIfAvailable();
        if (messaging == null) {
            return;
        }
        try {
            messaging.convertAndSend(destination, event);
        } catch (RuntimeException failed) {
            log.warn("Chat push {} to {} failed; clients catch up over REST", event.type(), destination, failed);
        }
    }

    private void sendToUser(UUID userId, ChatEvent event) {
        SimpMessagingTemplate messaging = template.getIfAvailable();
        if (messaging == null || userId == null) {
            return;
        }
        try {
            messaging.convertAndSendToUser(userId.toString(), ChatTopics.USER_QUEUE, event);
        } catch (RuntimeException failed) {
            log.warn("Chat push {} to a user queue failed; clients catch up over REST", event.type(), failed);
        }
    }
}

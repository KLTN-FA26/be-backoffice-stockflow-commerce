package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomerChatReplied;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * E-mails a customer that Sales answered them in the chat while they were away (kltn-docs 16 §4.1 step 3), through the
 * customer mail every customer message goes through: skipped where customer mail is off, once per message reference
 * (the conversation and the notice period). The in-app notice was already posted by the chat in the reply's own
 * transaction.
 */
@Component
class ChatReplyNotificationListener {

    private final CustomerEmailComposer composer;
    private final CustomerEmailDelivery delivery;

    ChatReplyNotificationListener(CustomerEmailComposer composer, CustomerEmailDelivery delivery) {
        this.composer = composer;
        this.delivery = delivery;
    }

    @Async
    @TransactionalEventListener
    public void on(CustomerChatReplied event) {
        composer.chatReplied(event).ifPresent(delivery::deliver);
    }
}

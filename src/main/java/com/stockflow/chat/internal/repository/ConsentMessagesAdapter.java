package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.order.api.ConsentMessages;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The chat module's answer to order about a message offered as a customer's consent (4B-8, kltn-docs 17 BR-28): its
 * conversation's customer and order, and whether the customer sent it. See {@link ConsentMessages} for why order does
 * not read the chat itself.
 */
@Component
class ConsentMessagesAdapter implements ConsentMessages {

    private final MessageJpaRepository messages;
    private final ConversationJpaRepository conversations;

    ConsentMessagesAdapter(MessageJpaRepository messages, ConversationJpaRepository conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> find(UUID messageId) {
        return messages.findById(messageId).flatMap(message -> conversations.findById(message.getConversationId())
                .map(conversation -> new Message(messageId, conversation.getCustomerId(), conversation.getOrderId(),
                        message.getSenderType() == SenderType.CUSTOMER)));
    }
}

package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.chat.internal.entity.ConversationJpaEntity;
import com.stockflow.chat.internal.entity.MessageJpaEntity;
import com.stockflow.order.api.ConsentMessages;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The chat module's answer to order about a message offered as a customer's consent (4B-8, kltn-docs 17 BR-28): its
 * conversation's customer and order, whether the customer sent it, and what it says (the picker and the window's
 * history). See {@link ConsentMessages} for why order does not read the chat itself.
 */
@Component
class ConsentMessagesAdapter implements ConsentMessages {

    /** What the customer can say as consent: written text, or files with an optional caption. */
    private static final List<MessageKind> CONSENT_KINDS = List.of(MessageKind.TEXT, MessageKind.ATTACHMENT);

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

    @Override
    @Transactional(readOnly = true)
    public List<Excerpt> customerMessages(UUID customerId, UUID orderId, int limit) {
        List<MessageJpaEntity> found = messages.sentByCustomer(customerId, orderId, SenderType.CUSTOMER,
                CONSENT_KINDS, PageRequest.of(0, Math.max(1, limit)));
        return excerptsOf(found);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Excerpt> excerpts(Collection<UUID> messageIds) {
        Map<UUID, Excerpt> byId = new HashMap<>();
        excerptsOf(messages.findAllById(messageIds)).forEach(e -> byId.put(e.messageId(), e));
        return byId;
    }

    /** Keeps the order of {@code found}. */
    private List<Excerpt> excerptsOf(Collection<MessageJpaEntity> found) {
        Map<UUID, ConversationJpaEntity> byConversation = new HashMap<>();
        conversations.findAllById(found.stream().map(MessageJpaEntity::getConversationId).distinct().toList())
                .forEach(c -> byConversation.put(c.getId(), c));
        return found.stream().map(m -> {
            boolean deleted = m.getDeletedAt() != null;
            ConversationJpaEntity conversation = byConversation.get(m.getConversationId());
            String text = deleted || m.getBody() == null || m.getBody().isBlank() ? null : m.getBody().strip();
            return new Excerpt(m.getId(), m.getKind().name(), text, conversation == null ? null
                    : conversation.getSubject(), m.getSentAt(), deleted);
        }).toList();
    }
}

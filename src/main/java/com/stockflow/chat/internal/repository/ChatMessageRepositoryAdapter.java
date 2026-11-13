package com.stockflow.chat.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.entity.MessageAttachmentJpaEntity;
import com.stockflow.chat.internal.entity.MessageJpaEntity;
import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements the {@link ChatMessageRepository} port. Every read takes the {@link Audience} and passes its visibilities
 * to the query: the filter that keeps internal notes from customers (16 BR-07) lives here and nowhere else.
 *
 * <p>A page of messages is three statements however long it is: the messages, their files, their mentions.</p>
 */
@Repository
class ChatMessageRepositoryAdapter implements ChatMessageRepository {

    private final MessageJpaRepository messages;
    private final MessageAttachmentJpaRepository attachments;
    private final ObjectMapper json;

    @PersistenceContext
    private EntityManager entityManager;

    ChatMessageRepositoryAdapter(MessageJpaRepository messages, MessageAttachmentJpaRepository attachments,
                                 ObjectMapper json) {
        this.messages = messages;
        this.attachments = attachments;
        this.json = json;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        Optional<MessageJpaEntity> existing = messages.findById(message.id());
        MessageJpaEntity entity = existing.orElseGet(() -> new MessageJpaEntity(message.id(),
                message.conversationId(), message.seq(), message.kind(), message.visibility(), message.senderType(),
                message.senderId(), message.clientMessageId(), message.contextId(),
                message.card() == null ? null : message.card().kind(), message.sentAt()));
        ChatMessage.Card card = message.card();
        entity.apply(message.body(), message.editedAt(), card == null ? null : parse(card.payloadJson()),
                card == null ? null : card.state(), card == null ? null : card.updatedAt(), message.deletedAt(),
                message.deletedBy());
        MessageJpaEntity saved = messages.saveAndFlush(entity);
        if (existing.isEmpty()) {
            attachments.saveAllAndFlush(message.attachments().stream().map(a -> new MessageAttachmentJpaEntity(a.id(),
                    message.id(), message.kind(), a.fileKey(), a.originalName(), a.contentType(), a.sizeBytes(),
                    a.sha256(), a.width(), a.height(), a.uploadedBy())).toList());
            for (UUID user : message.mentions()) {
                entityManager.createNativeQuery("""
                                insert into chat.message_mention (id, message_id, message_kind, user_id, created_at)
                                values (:id, :message, 'INTERNAL_NOTE', :user, :at)""")
                        .setParameter("id", Identifiers.newId()).setParameter("message", message.id())
                        .setParameter("user", user).setParameter("at", message.sentAt()).executeUpdate();
            }
        }
        return toDomain(List.of(saved)).get(0);
    }

    @Override
    public Optional<ChatMessage> findById(UUID id, Audience audience) {
        return messages.findVisible(id, audience.sees()).map(m -> toDomain(List.of(m)).get(0));
    }

    @Override
    public Optional<ChatMessage> findByAttachment(UUID attachmentId, Audience audience) {
        return attachments.findById(attachmentId).flatMap(a -> findById(a.getMessageId(), audience));
    }

    @Override
    public Optional<ChatMessage> findByClientMessageId(UUID conversationId, String clientMessageId) {
        if (clientMessageId == null) {
            return Optional.empty();
        }
        return messages.findByConversationIdAndClientMessageId(conversationId, clientMessageId)
                .map(m -> toDomain(List.of(m)).get(0));
    }

    @Override
    public List<ChatMessage> before(UUID conversationId, Audience audience, Long beforeSeq, int limit) {
        return toDomain(messages.before(conversationId, audience.sees(), beforeSeq == null ? Long.MAX_VALUE : beforeSeq,
                PageRequest.of(0, limit)));
    }

    @Override
    public List<ChatMessage> after(UUID conversationId, Audience audience, long afterSeq, int limit) {
        return toDomain(messages.after(conversationId, audience.sees(), afterSeq, PageRequest.of(0, limit)));
    }

    @Override
    public Map<UUID, ChatMessage> lastOf(Collection<UUID> conversationIds, Audience audience) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        select distinct on (m.conversation_id) m.id from chat.message m
                         where m.conversation_id in (:conversations) and m.visibility in (:visible)
                         order by m.conversation_id, m.seq desc""")
                .setParameter("conversations", conversationIds).setParameter("visible", names(audience))
                .getResultList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return toDomain(messages.findAllById(ids)).stream()
                .collect(Collectors.toMap(ChatMessage::conversationId, m -> m));
    }

    @Override
    public Map<UUID, Long> unread(Collection<UUID> conversationIds, UUID readerId, Audience audience) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select m.conversation_id, count(*) from chat.message m
                          left join chat.participant_read r on r.conversation_id = m.conversation_id
                                                           and r.user_id = :reader
                         where m.conversation_id in (:conversations) and m.visibility in (:visible)
                           and m.seq > coalesce(r.last_read_seq, 0) and m.deleted_at is null
                           and (m.sender_id is null or m.sender_id <> :reader)
                         group by m.conversation_id""")
                .setParameter("conversations", conversationIds).setParameter("visible", names(audience))
                .setParameter("reader", readerId).getResultList();
        Map<UUID, Long> counts = new HashMap<>();
        rows.forEach(row -> counts.put((UUID) row[0], ((Number) row[1]).longValue()));
        return counts;
    }

    private static List<String> names(Audience audience) {
        return audience.sees().stream().map(Enum::name).toList();
    }

    private List<ChatMessage> toDomain(List<MessageJpaEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(MessageJpaEntity::getId).toList();
        Map<UUID, List<ChatMessage.Attachment>> files = new HashMap<>();
        attachments.findByMessageIdIn(ids).stream()
                .sorted(java.util.Comparator.comparing(MessageAttachmentJpaEntity::getId))
                .forEach(a -> files.computeIfAbsent(a.getMessageId(), k -> new ArrayList<>())
                        .add(new ChatMessage.Attachment(a.getId(), a.getFileKey(), a.getOriginalName(),
                                a.getContentType(), a.getSizeBytes(), a.getSha256(), a.getWidth(), a.getHeight(),
                                a.getUploadedBy())));
        Map<UUID, Set<UUID>> mentions = new HashMap<>();
        if (rows.stream().anyMatch(r -> r.getKind() == com.stockflow.chat.internal.domain.MessageKind.INTERNAL_NOTE)) {
            @SuppressWarnings("unchecked")
            List<Object[]> named = entityManager.createNativeQuery(
                            "select message_id, user_id from chat.message_mention where message_id in (:ids)")
                    .setParameter("ids", ids).getResultList();
            named.forEach(row -> mentions.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((UUID) row[1]));
        }
        return rows.stream().map(m -> new ChatMessage(m.getId(), m.getConversationId(), m.getSeq(), m.getKind(),
                m.getVisibility(), m.getSenderType(), m.getSenderId(), m.getBody(), m.getClientMessageId(),
                m.getContextId(), m.getCardKind() == null ? null : new ChatMessage.Card(m.getCardKind(),
                        m.getCardPayload() == null ? "{}" : m.getCardPayload().toString(), m.getCardState(),
                        m.getCardUpdatedAt()), m.getSentAt(), m.getEditedAt(), m.getDeletedAt(), m.getDeletedBy(),
                mentions.getOrDefault(m.getId(), Set.of()), files.getOrDefault(m.getId(), List.of()))).toList();
    }

    private JsonNode parse(String payloadJson) {
        if (payloadJson == null) {
            return null;
        }
        try {
            JsonNode node = json.readTree(payloadJson);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("A card's payload is a JSON object");
            }
            return node;
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("A card's payload is a JSON object", malformed);
        }
    }
}

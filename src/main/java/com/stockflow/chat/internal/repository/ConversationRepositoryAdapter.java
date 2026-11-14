package com.stockflow.chat.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.InboxFilter;
import com.stockflow.chat.internal.domain.LogEntry;
import com.stockflow.chat.internal.domain.Slice;
import com.stockflow.chat.internal.entity.ConversationContextJpaEntity;
import com.stockflow.chat.internal.entity.ConversationEventJpaEntity;
import com.stockflow.chat.internal.entity.ConversationJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements the {@link ConversationRepository} port: the conversation row, its contexts and its log.
 *
 * <p>The lists are native SQL, each one statement for the page and one for the count, because the queue's order
 * (the overdue first, BR-09) and the "about this document" filter are easier to state, and to index, in SQL
 * than in the criteria API. Each list returns ids in order and loads the rows by id.</p>
 */
@Repository
class ConversationRepositoryAdapter implements ConversationRepository {

    private static final String ENDED = "('RESOLVED', 'AUTO_CLOSED', 'CLOSED')";
    /**
     * BR-09 in the queue: the first answer is late, or the resolution is (Conversation#firstResponseOverdueAt,
     * #resolutionOverdueAt). One predicate for the overdue filter and the order, so the two never disagree.
     */
    private static final String OVERDUE = "(c.status not in " + ENDED + " and ((c.first_responded_at is null"
            + " and c.first_response_due_at < :now) or c.resolution_due_at < :now))";
    /** The context row's document, whatever its kind: exactly one of these columns is set (ck_conversation_context_reference). */
    private static final String REFERENCE = "coalesce(x.product_id, x.variant_id, x.design_id, x.design_project_id, "
            + "x.quote_id, x.order_id, x.contract_id, x.production_order_id, x.einvoice_id)";
    private static final String ABOUT = " and exists (select 1 from chat.conversation_context x where x.conversation_id"
            + " = c.id and x.kind = :aboutKind and " + REFERENCE + " = :aboutId)";

    private final ConversationJpaRepository conversations;
    private final ConversationContextJpaRepository contexts;
    private final ConversationEventJpaRepository events;
    private final ObjectMapper json;

    @PersistenceContext
    private EntityManager entityManager;

    ConversationRepositoryAdapter(ConversationJpaRepository conversations, ConversationContextJpaRepository contexts,
                                  ConversationEventJpaRepository events, ObjectMapper json) {
        this.conversations = conversations;
        this.contexts = contexts;
        this.events = events;
        this.json = json;
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        return conversations.findById(id).map(ConversationRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return conversations.existsById(id);
    }

    /**
     * Native {@code select ... for update}, then a refresh: a plain {@code findById} would hand back the copy already in
     * the persistence context and the lock would guard a row the caller never re-read.
     */
    @Override
    public Optional<Conversation> findByIdForUpdate(UUID id) {
        @SuppressWarnings("unchecked")
        List<UUID> locked = entityManager.createNativeQuery("select id from chat.conversation where id = :id for update")
                .setParameter("id", id).getResultList();
        return locked.stream().findFirst().flatMap(conversations::findById).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    @Override
    public Conversation save(Conversation conversation) {
        Conversation.Snapshot s = conversation.snapshot();
        ConversationJpaEntity entity = conversations.findById(s.id()).orElseGet(() -> new ConversationJpaEntity(s.id(),
                s.customerId(), s.orderId(), s.subject(), s.openedByType(), s.openedBy(), s.continuedFromId()));
        entity.apply(s.status(), s.assignedAgentId(), s.assignedAt(), s.statusChangedAt(), s.firstResponseDueAt(),
                s.firstRespondedAt(), s.firstResponseAlertedAt(), s.resolutionDueAt(), s.resolutionAlertedAt(),
                s.escalationReason(), s.escalatedToId(), s.resolvedAt(), s.resolvedBy(), s.autoClosedAt(),
                s.closedAt(), s.reopenUntil(), s.lastCustomerMessageAt(), s.lastMessageAt(), s.lastMessageSeq(),
                s.csatScore(), s.csatComment(), s.csatSubmittedAt(), s.mergedIntoId());
        return toDomain(conversations.saveAndFlush(entity));
    }

    /** With no {@code ref}: the newest open conversation about nothing in particular (no context at all). */
    @Override
    public Optional<Conversation> findOpenAbout(UUID customerId, ContextRef ref) {
        String about = ref == null
                ? " and not exists (select 1 from chat.conversation_context x where x.conversation_id = c.id)" : ABOUT;
        Query query = entityManager.createNativeQuery("select c.id from chat.conversation c where c.customer_id = "
                        + ":customer and c.status not in " + ENDED + about + " order by c.created_at desc, c.id desc"
                        + " limit 1")
                .setParameter("customer", customerId);
        if (ref != null) {
            query.setParameter("aboutKind", ref.kind().name()).setParameter("aboutId", ref.id());
        }
        @SuppressWarnings("unchecked")
        List<UUID> found = query.getResultList();
        return found.stream().findFirst().flatMap(this::findById);
    }

    @Override
    public Slice<Conversation> ofCustomer(UUID customerId, ContextRef about, int page, int size) {
        StringBuilder where = new StringBuilder(" where c.customer_id = :customer");
        Map<String, Object> params = new HashMap<>();
        params.put("customer", customerId);
        about(where, params, about);
        return slice(where.toString(), " order by coalesce(c.last_message_at, c.created_at) desc, c.id desc", params,
                page, size);
    }

    @Override
    public Slice<Conversation> inbox(InboxFilter filter, Instant now, int page, int size) {
        StringBuilder where = new StringBuilder(" where c.status in (:statuses)");
        Map<String, Object> params = new HashMap<>();
        params.put("statuses", filter.statuses().stream().map(Enum::name).toList());
        params.put("now", now);
        if (filter.assignedTo() != null) {
            where.append(" and c.assigned_agent_id = :agent");
            params.put("agent", filter.assignedTo());
        }
        if (filter.unassigned()) {
            where.append(" and c.assigned_agent_id is null");
        }
        if (filter.overdue()) {
            where.append(" and ").append(OVERDUE);
        }
        if (filter.customerId() != null) {
            where.append(" and c.customer_id = :customer");
            params.put("customer", filter.customerId());
        }
        about(where, params, filter.about());
        return slice(where.toString(), " order by " + OVERDUE + " desc, coalesce(c.last_message_at, c.created_at)"
                + " desc, c.id desc", params, page, size);
    }

    @Override
    public List<Conversation> about(ContextRef ref, int limit) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("select c.id from chat.conversation c where true" + ABOUT
                        + " order by c.created_at desc, c.id desc limit :limit")
                .setParameter("aboutKind", ref.kind().name()).setParameter("aboutId", ref.id())
                .setParameter("limit", limit).getResultList();
        return load(ids);
    }

    @Override
    public List<ConversationContext> contextsOf(UUID conversationId) {
        return contexts.findByConversationIdOrderByAttachedAtAscIdAsc(conversationId).stream()
                .map(ConversationRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Map<UUID, List<ConversationContext>> contextsOf(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return contexts.findByConversationIdIn(conversationIds).stream()
                .sorted(java.util.Comparator.comparing(ConversationContextJpaEntity::getAttachedAt)
                        .thenComparing(ConversationContextJpaEntity::getId))
                .map(ConversationRepositoryAdapter::toDomain)
                .collect(Collectors.groupingBy(ConversationContext::conversationId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    @Override
    public Optional<ConversationContext> findContext(UUID conversationId, ContextRef ref) {
        return contextsOf(conversationId).stream().filter(c -> c.ref().equals(ref)).findFirst();
    }

    @Override
    public Optional<ConversationContext> findContext(UUID conversationId, UUID contextId) {
        return contexts.findById(contextId).filter(c -> c.getConversationId().equals(conversationId))
                .map(ConversationRepositoryAdapter::toDomain);
    }

    @Override
    public ConversationContext attach(ConversationContext context) {
        return toDomain(contexts.saveAndFlush(new ConversationContextJpaEntity(context.id(), context.conversationId(),
                context.ref().kind(), context.ref().id(), context.primary(), context.attachedBy(),
                context.attachedAt())));
    }

    @Override
    public void append(List<LogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        events.saveAllAndFlush(entries.stream().map(e -> new ConversationEventJpaEntity(e.id(), e.conversationId(),
                e.type(), e.actorType(), e.actorId(), e.at(), e.from(), e.to(), e.contextId(), e.messageId(),
                e.targetUserId(), e.note(), parse(e.detailJson()))).toList());
    }

    @Override
    public List<LogEntry> logOf(UUID conversationId) {
        return events.findByConversationIdOrderByOccurredAtAscIdAsc(conversationId).stream()
                .map(e -> new LogEntry(e.getId(), e.getConversationId(), e.getType(), e.getActorType(), e.getActorId(),
                        e.getOccurredAt(), e.getFromStatus(), e.getToStatus(), e.getContextId(), e.getMessageId(),
                        e.getTargetUserId(), e.getNote(), e.getDetail() == null ? null : e.getDetail().toString()))
                .toList();
    }

    @Override
    public Optional<Conversation> newestOpenOf(UUID customerId) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery("select c.id from chat.conversation c where c.customer_id ="
                        + " :customer and c.status not in " + ENDED
                        + " order by coalesce(c.last_message_at, c.created_at) desc, c.id desc limit 1")
                .setParameter("customer", customerId).getResultList();
        return found.stream().findFirst().flatMap(this::findById);
    }

    /** One row per agent, at their latest conversation with the customer. */
    @Override
    public List<UUID> recentAgentsOf(UUID customerId, UUID except, int limit) {
        @SuppressWarnings("unchecked")
        List<UUID> agents = entityManager.createNativeQuery("""
                        select a.agent from (select c.assigned_agent_id as agent,
                                                    max(coalesce(c.last_message_at, c.created_at)) as last_at
                                               from chat.conversation c
                                              where c.customer_id = :customer and c.assigned_agent_id is not null
                                                and c.id <> :except
                                              group by c.assigned_agent_id) a
                         order by a.last_at desc limit :limit""")
                .setParameter("customer", customerId).setParameter("except", except == null ? new UUID(0L, 0L) : except)
                .setParameter("limit", limit).getResultList();
        return agents;
    }

    @Override
    public List<UUID> firstResponseOverdueUnalerted(Instant now, int limit) {
        return ids("""
                select c.id from chat.conversation c
                 where c.first_responded_at is null and c.first_response_alerted_at is null
                   and c.first_response_due_at < :at and c.status not in""" + " " + ENDED + """
                 order by c.first_response_due_at, c.id limit :limit""", now, limit);
    }

    @Override
    public List<UUID> resolutionOverdueUnalerted(Instant now, int limit) {
        return ids("""
                select c.id from chat.conversation c
                 where c.resolution_alerted_at is null and c.resolution_due_at < :at
                   and c.status not in""" + " " + ENDED + """
                 order by c.resolution_due_at, c.id limit :limit""", now, limit);
    }

    @Override
    public List<UUID> waitingOnCustomerSince(Instant silentSince, int limit) {
        return ids("""
                select c.id from chat.conversation c
                 where c.status = 'WAITING_ON_CUSTOMER' and c.status_changed_at < :at
                 order by c.status_changed_at, c.id limit :limit""", silentSince, limit);
    }

    @Override
    public List<UUID> pastReopenWindow(Instant now, int limit) {
        return ids("""
                select c.id from chat.conversation c
                 where c.status in ('RESOLVED', 'AUTO_CLOSED') and c.reopen_until <= :at
                 order by c.reopen_until, c.id limit :limit""", now, limit);
    }

    @Override
    public List<ConversationFacts> openedBetween(Instant from, Instant to, UUID agentId, int limit) {
        Query query = entityManager.createNativeQuery("""
                        select c.id, c.assigned_agent_id, c.created_at, c.first_responded_at,
                               c.first_response_alerted_at, c.resolved_at, c.csat_score
                          from chat.conversation c
                         where c.created_at >= :from and c.created_at < :to""" + (agentId == null ? ""
                        : " and c.assigned_agent_id = :agent") + " order by c.created_at, c.id limit :limit")
                .setParameter("from", from).setParameter("to", to).setParameter("limit", limit);
        if (agentId != null) {
            query.setParameter("agent", agentId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(r -> new ConversationFacts((UUID) r[0], (UUID) r[1], instant(r[2]), instant(r[3]),
                instant(r[4]), instant(r[5]), r[6] == null ? null : ((Number) r[6]).intValue())).toList();
    }

    private List<UUID> ids(String sql, Instant at, int limit) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery(sql).setParameter("at", at).setParameter("limit", limit)
                .getResultList();
        return found;
    }

    /** A timestamptz column as the native query hands it back (Instant, OffsetDateTime or Timestamp by driver). */
    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Not a timestamp: " + value.getClass());
    }

    private void about(StringBuilder where, Map<String, Object> params, ContextRef about) {
        if (about != null) {
            where.append(ABOUT);
            params.put("aboutKind", about.kind().name());
            params.put("aboutId", about.id());
        }
    }

    private Slice<Conversation> slice(String where, String order, Map<String, Object> params, int page, int size) {
        Query select = entityManager.createNativeQuery("select c.id from chat.conversation c" + where + order
                + " limit :size offset :offset");
        Query count = entityManager.createNativeQuery("select count(*) from chat.conversation c" + where);
        params.forEach((name, value) -> {
            select.setParameter(name, value);
            if (!"now".equals(name) || where.contains(":now")) {
                count.setParameter(name, value);
            }
        });
        select.setParameter("size", size).setParameter("offset", (long) page * size);
        @SuppressWarnings("unchecked")
        List<UUID> ids = select.getResultList();
        long total = ((Number) count.getSingleResult()).longValue();
        return new Slice<>(load(ids), total);
    }

    private List<Conversation> load(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, ConversationJpaEntity> byId = conversations.findAllById(ids).stream()
                .collect(Collectors.toMap(ConversationJpaEntity::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(ConversationRepositoryAdapter::toDomain).toList();
    }

    private JsonNode parse(String detailJson) {
        if (detailJson == null) {
            return null;
        }
        try {
            return json.readTree(detailJson);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("A log line's detail is a JSON object", malformed);
        }
    }

    private static ConversationContext toDomain(ConversationContextJpaEntity e) {
        return new ConversationContext(e.getId(), e.getConversationId(), new ContextRef(e.getKind(), e.getReference()),
                e.isPrimary(), e.getAttachedBy(), e.getAttachedAt());
    }

    private static Conversation toDomain(ConversationJpaEntity e) {
        return Conversation.restore(new Conversation.Snapshot(e.getId(), e.getCustomerId(), e.getOrderId(),
                e.getSubject(), e.getStatus(), e.getOpenedByType(), e.getOpenedBy(), e.getAssignedAgentId(),
                e.getAssignedAt(), e.getStatusChangedAt(), e.getFirstResponseDueAt(), e.getFirstRespondedAt(),
                e.getFirstResponseAlertedAt(), e.getResolutionDueAt(), e.getResolutionAlertedAt(),
                e.getEscalationReason(), e.getEscalatedToId(), e.getResolvedAt(), e.getResolvedBy(),
                e.getAutoClosedAt(), e.getClosedAt(), e.getReopenUntil(), e.getLastCustomerMessageAt(),
                e.getLastMessageAt(), e.getLastMessageSeq(),
                e.getCsatScore() == null ? null : e.getCsatScore().intValue(), e.getCsatComment(),
                e.getCsatSubmittedAt(), e.getContinuedFromId(), e.getMergedIntoId(), e.getCreatedAt(),
                e.getVersion()));
    }
}

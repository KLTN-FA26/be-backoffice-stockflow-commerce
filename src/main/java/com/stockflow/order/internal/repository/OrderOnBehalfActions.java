package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ordering.order_on_behalf_action} (V20270128000310): the operational work done on an order for another
 * salesperson's customer (kltn-docs 17 BR-32), append-only. Plain rows with no life of their own, as the order's other
 * history entries that move no status ({@link OrderHistoryNotes}).
 */
@Repository
public class OrderOnBehalfActions {

    /** One entry of the order's history: {@code action} done by {@code actorId} for {@code actingForUserId}. */
    public record Entry(UUID id, String action, UUID actorId, UUID actingForUserId, Instant actedAt) {
    }

    /** What the log needs of the order: whose it is and its number. */
    public record OrderFacts(UUID customerId, String orderNumber) {
    }

    private final EntityManager entityManager;

    public OrderOnBehalfActions(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The order's customer and number; empty for no such order. */
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    public Optional<OrderFacts> orderFacts(UUID orderId) {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT customer_id, order_number FROM ordering.customer_order WHERE id = :id")
                .setParameter("id", orderId).getResultList();
        return rows.stream().findFirst().map(row -> new OrderFacts((UUID) row[0], (String) row[1]));
    }

    /** Appends one entry, in the caller's transaction (the action's own); answers its id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(UUID orderId, String action, UUID actorId, UUID actingForUserId, Instant at) {
        UUID id = Identifiers.newId();
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id,
                                                                     acted_at)
                        VALUES (:id, :order, :action, :actor, CAST(:for AS UUID), :at)""")
                .setParameter("id", id).setParameter("order", orderId).setParameter("action", action)
                .setParameter("actor", actorId).setParameter("for", actingForUserId)
                .setParameter("at", Timestamp.from(at))
                .executeUpdate();
        return id;
    }

    /** The order's entries, oldest first. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Entry> of(UUID orderId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, action, actor_id, acting_for_user_id, acted_at
                          FROM ordering.order_on_behalf_action
                         WHERE order_id = :order ORDER BY acted_at, id""")
                .setParameter("order", orderId).getResultList();
        List<Entry> entries = new ArrayList<>();
        for (Object[] row : rows) {
            entries.add(new Entry((UUID) row[0], (String) row[1], (UUID) row[2], (UUID) row[3], instant(row[4])));
        }
        return entries;
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }
}

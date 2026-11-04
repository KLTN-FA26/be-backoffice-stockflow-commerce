package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.SettlementCandidates;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Native SQL on {@code ordering.customer_order} and {@code ordering.return_request}, as {@code CreditChecksAdapter}. */
@Repository
class SettlementCandidatesAdapter implements SettlementCandidates {

    /**
     * The statuses of a return still open (BR-ORD-005: it keeps its order from completing), written from
     * {@link ReturnStatus#isOpen} rather than by hand: the list written here before the returns had a writer named
     * statuses that no longer exist, and a status added later must not slip past the check.
     */
    private static final String OPEN_RETURN = Arrays.stream(ReturnStatus.values()).filter(ReturnStatus::isOpen)
            .map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));

    private final EntityManager entityManager;

    SettlementCandidatesAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> deliveredBy(Instant deliveredBy, int limit) {
        return entityManager.createNativeQuery("""
                        SELECT o.id FROM ordering.customer_order o
                         WHERE o.status = 'DELIVERED' AND o.delivered_at <= :cutoff
                           -- Still owing, or a return open: the aggregate would refuse; leave room in the batch.
                           AND o.paid_amount >= o.total_amount
                               - COALESCE((SELECT SUM(l.cancelled_quantity * l.unit_price)
                                             FROM ordering.order_line l WHERE l.order_id = o.id), 0)
                           AND NOT EXISTS (SELECT 1 FROM ordering.return_request r WHERE r.order_id = o.id
                                              AND r.status IN (%s))
                         ORDER BY o.delivered_at, o.id""".formatted(OPEN_RETURN))
                .setParameter("cutoff", deliveredBy)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> settledBy(Instant settledBy, int limit) {
        return entityManager.createNativeQuery("""
                        SELECT o.id FROM ordering.customer_order o
                         WHERE ((o.status = 'COMPLETED' AND o.completed_at <= :cutoff)
                            OR (o.status IN ('REFUNDED', 'PARTIALLY_REFUNDED') AND o.refunded_at <= :cutoff))
                           -- A return still open on a settled order (a claim opened before the order completed) keeps
                           -- it from closing for good (BR-05): CLOSED is never changed again.
                           AND NOT EXISTS (SELECT 1 FROM ordering.return_request r WHERE r.order_id = o.id
                                              AND r.status IN (%s))
                         ORDER BY COALESCE(o.completed_at, o.refunded_at), o.id""".formatted(OPEN_RETURN))
                .setParameter("cutoff", settledBy)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public boolean hasOpenReturn(UUID orderId) {
        Object found = entityManager.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM ordering.return_request
                                        WHERE order_id = :order AND status IN (%s))""".formatted(OPEN_RETURN))
                .setParameter("order", orderId)
                .getSingleResult();
        return Boolean.TRUE.equals(found);
    }
}

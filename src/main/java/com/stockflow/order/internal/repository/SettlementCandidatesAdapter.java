package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SettlementCandidates;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Native SQL on {@code ordering.customer_order} and {@code ordering.return_request}, as {@code CreditChecksAdapter}. */
@Repository
class SettlementCandidatesAdapter implements SettlementCandidates {

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
                                              AND r.status IN ('REQUESTED', 'APPROVED', 'IN_TRANSIT', 'RECEIVED'))
                         ORDER BY o.delivered_at, o.id""")
                .setParameter("cutoff", deliveredBy)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> settledBy(Instant settledBy, int limit) {
        return entityManager.createNativeQuery("""
                        SELECT id FROM ordering.customer_order
                         WHERE (status = 'COMPLETED' AND completed_at <= :cutoff)
                            OR (status IN ('REFUNDED', 'PARTIALLY_REFUNDED') AND refunded_at <= :cutoff)
                         ORDER BY COALESCE(completed_at, refunded_at), id""")
                .setParameter("cutoff", settledBy)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public boolean hasOpenReturn(UUID orderId) {
        Object found = entityManager.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM ordering.return_request
                                        WHERE order_id = :order
                                          AND status IN ('REQUESTED', 'APPROVED', 'IN_TRANSIT', 'RECEIVED'))""")
                .setParameter("order", orderId)
                .getSingleResult();
        return Boolean.TRUE.equals(found);
    }
}

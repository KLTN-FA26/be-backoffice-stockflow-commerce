package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.SalesFollowUps;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL on {@code ordering.sales_contract} and {@code ordering.customer_order}, on the partial indexes
 * {@code ix_sales_contract_sent_unsigned} and {@code ix_customer_order_awaiting_on_signing} (V20261124000200).
 */
@Repository
class SalesFollowUpsAdapter implements SalesFollowUps {

    private final EntityManager entityManager;

    SalesFollowUpsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UnsignedContract> unsignedSince(Instant sentBy, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT c.id, c.contract_number, c.order_id, o.order_number, c.sent_by, c.sent_at
                          FROM ordering.sales_contract c
                          JOIN ordering.customer_order o ON o.id = c.order_id
                         WHERE c.status = 'SENT' AND c.sent_at <= :cutoff AND c.sent_by IS NOT NULL
                         ORDER BY c.sent_at DESC, c.id
                         LIMIT :limit""")
                .setParameter("cutoff", sentBy)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(r -> new UnsignedContract((UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3],
                (UUID) r[4], instant(r[5]))).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UnpaidOrder> unpaidSince(Instant signedBy, int limit) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT o.id, o.order_number, c.id, c.contract_number, COALESCE(c.sent_by, c.signed_by),
                               o.contract_signed_at
                          FROM ordering.customer_order o
                          JOIN ordering.sales_contract c ON c.order_id = o.id AND c.status = 'SIGNED'
                         WHERE o.status = 'PENDING_PAYMENT' AND o.contract_signed_at <= :cutoff
                           AND COALESCE(c.sent_by, c.signed_by) IS NOT NULL
                         ORDER BY o.contract_signed_at DESC, o.id
                         LIMIT :limit""")
                .setParameter("cutoff", signedBy)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(r -> new UnpaidOrder((UUID) r[0], (String) r[1], (UUID) r[2], (String) r[3],
                (UUID) r[4], instant(r[5]))).toList();
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

package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.internal.domain.CreditChecks;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Native SQL on {@code ordering.order_credit_check} and the order table, as {@code ProductionRecordsAdapter}. */
@Repository
class CreditChecksAdapter implements CreditChecks, CreditHoldSearch {

    private final EntityManager entityManager;

    CreditChecksAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public BigDecimal undeliveredCreditExposure(UUID customerId, UUID excludingOrderId) {
        Object sum = entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(o.total_amount - o.paid_amount), 0)
                          FROM ordering.customer_order o
                         WHERE o.customer_id = :customer
                           AND o.payment_term = 'CREDIT'
                           AND o.status IN ('CONFIRMED', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'IN_FULFILMENT',
                                            'ON_HOLD', 'SHIPPED')
                           AND o.id <> :excluding
                           AND NOT EXISTS (SELECT 1 FROM ordering.order_hold h
                                            WHERE h.order_id = o.id AND h.reason = 'CREDIT' AND h.resolved_at IS NULL)""")
                .setParameter("customer", customerId)
                .setParameter("excluding", excludingOrderId == null ? new UUID(0, 0) : excludingOrderId)
                .getSingleResult();
        return sum instanceof BigDecimal value ? value : new BigDecimal(sum.toString());
    }

    @Override
    public void record(Check check) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_credit_check (id, order_id, checked_at, credit_limit, exposure,
                                                                 order_amount, outcome, decided_by, decided_at, note)
                        VALUES (:id, :order, :at, :limit, :exposure, :amount, :outcome, :by, :decidedAt, :note)""")
                .setParameter("id", Identifiers.newId())
                .setParameter("order", check.orderId())
                .setParameter("at", check.checkedAt())
                .setParameter("limit", check.creditLimit())
                .setParameter("exposure", check.exposure())
                .setParameter("amount", check.orderAmount())
                .setParameter("outcome", check.outcome().name())
                .setParameter("by", check.decidedBy())
                .setParameter("decidedAt", check.decidedAt())
                .setParameter("note", check.note())
                .executeUpdate();
    }

    @Override
    public Optional<Check> latest(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT order_id, checked_at, credit_limit, exposure, order_amount, outcome, decided_by,
                               decided_at, note
                          FROM ordering.order_credit_check WHERE order_id = :order
                         ORDER BY checked_at DESC, created_at DESC""")
                .setParameter("order", orderId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst().map(r -> new Check((UUID) r[0], instant(r[1]), (BigDecimal) r[2],
                (BigDecimal) r[3], (BigDecimal) r[4], Outcome.valueOf((String) r[5]), (UUID) r[6], instant(r[7]),
                (String) r[8]));
    }

    @Override
    public org.springframework.data.domain.Page<UUID> heldOrders(org.springframework.data.domain.Pageable pageable) {
        String held = " FROM ordering.customer_order o"
                + " JOIN ordering.order_hold h ON h.order_id = o.id AND h.reason = 'CREDIT' AND h.resolved_at IS NULL"
                + " WHERE o.status = 'ON_HOLD'";
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("SELECT o.id" + held + " ORDER BY h.raised_at, o.id")
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
        long total = ((Number) entityManager.createNativeQuery("SELECT count(*)" + held).getSingleResult()).longValue();
        return new org.springframework.data.domain.PageImpl<>(ids, pageable, total);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }
}

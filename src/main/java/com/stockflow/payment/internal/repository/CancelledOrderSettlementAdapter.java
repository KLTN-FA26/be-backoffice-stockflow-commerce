package com.stockflow.payment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL, as the order module's key tables: two statements on rows only this follower writes.
 * When the payment module grows its own aggregates (SCRUM-217/220) this becomes a call into them.
 */
@Repository
class CancelledOrderSettlementAdapter implements CancelledOrderSettlement {

    private final EntityManager entityManager;

    CancelledOrderSettlementAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public int cancelAwaitedPayments(UUID orderId) {
        return entityManager.createNativeQuery("""
                        UPDATE payment.payment SET status = 'CANCELLED', last_modified_at = NOW()
                         WHERE order_id = :order AND status = 'PENDING'""")
                .setParameter("order", orderId)
                .executeUpdate();
    }

    @Override
    public BigDecimal requestRefunds(UUID orderId, BigDecimal refundable, String currency, String reason) {
        return requestRefunds(orderId, refundable, currency, reason, "ORDER_CANCELLED");
    }

    @Override
    public BigDecimal requestRemainderRefunds(UUID orderId, UUID closureId, BigDecimal refundable, String currency,
                                              String reason) {
        Object asked = entityManager.createNativeQuery("""
                        SELECT count(*) FROM payment.refund
                         WHERE source = 'ORDER_REMAINDER_CLOSED' AND remainder_closure_id = :closure""")
                .setParameter("closure", closureId)
                .getSingleResult();
        if (((Number) asked).longValue() > 0) {
            return BigDecimal.ZERO;   // this closure was settled already: a redelivered event
        }
        // What is left of each captured payment after the refunds already asked of it (a cancellation, an earlier
        // closure, a return), newest first.
        @SuppressWarnings("unchecked")
        List<Object[]> captured = entityManager.createNativeQuery("""
                        SELECT p.id, p.amount - COALESCE((SELECT SUM(r.amount) FROM payment.refund r
                                                           WHERE r.payment_id = p.id AND r.status <> 'FAILED'), 0)
                          FROM payment.payment p
                         WHERE p.order_id = :order AND p.status = 'CAPTURED' AND p.currency = :currency
                         ORDER BY p.captured_at DESC NULLS LAST, p.id""")
                .setParameter("order", orderId)
                .setParameter("currency", currency)
                .getResultList();
        BigDecimal remaining = refundable;
        for (Object[] row : captured) {
            BigDecimal left = (BigDecimal) row[1];
            if (remaining.signum() <= 0) {
                break;
            }
            if (left.signum() <= 0) {
                continue;
            }
            BigDecimal amount = remaining.min(left);
            int inserted = entityManager.createNativeQuery("""
                            INSERT INTO payment.refund (id, payment_id, amount, currency, reason, status, created_at,
                                                        order_id, source, remainder_closure_id)
                            VALUES (:id, :payment, :amount, :currency, :reason, 'PENDING', NOW(), :order,
                                    'ORDER_REMAINDER_CLOSED', :closure)
                            ON CONFLICT (payment_id, remainder_closure_id) WHERE source = 'ORDER_REMAINDER_CLOSED'
                            DO NOTHING""")
                    .setParameter("id", Identifiers.newId())
                    .setParameter("payment", row[0])
                    .setParameter("amount", amount)
                    .setParameter("currency", currency)
                    .setParameter("reason", reason)
                    .setParameter("order", orderId)
                    .setParameter("closure", closureId)
                    .executeUpdate();
            if (inserted == 1) {    // only what was really asked for counts as placed
                remaining = remaining.subtract(amount);
            }
        }
        return remaining.max(BigDecimal.ZERO);
    }

    /** {@code source} is a constant of this class, never input: it is also the partial unique index's predicate. */
    private BigDecimal requestRefunds(UUID orderId, BigDecimal refundable, String currency, String reason,
                                      String source) {
        @SuppressWarnings("unchecked")
        List<Object[]> captured = entityManager.createNativeQuery("""
                        SELECT p.id, p.amount FROM payment.payment p
                         WHERE p.order_id = :order AND p.status = 'CAPTURED' AND p.currency = :currency
                         ORDER BY p.captured_at DESC NULLS LAST, p.id""")
                .setParameter("order", orderId)
                .setParameter("currency", currency)
                .getResultList();
        BigDecimal remaining = refundable;
        for (Object[] row : captured) {
            if (remaining.signum() <= 0) {
                break;
            }
            UUID paymentId = (UUID) row[0];
            BigDecimal amount = remaining.min((BigDecimal) row[1]);
            entityManager.createNativeQuery("""
                            INSERT INTO payment.refund (id, payment_id, amount, currency, reason, status, created_at,
                                                        order_id, source)
                            VALUES (:id, :payment, :amount, :currency, :reason, 'PENDING', NOW(), :order,
                                    '%1$s')
                            ON CONFLICT (payment_id) WHERE source = '%1$s' DO NOTHING""".formatted(source))
                    .setParameter("id", Identifiers.newId())
                    .setParameter("payment", paymentId)
                    .setParameter("amount", amount)
                    .setParameter("currency", currency)
                    .setParameter("reason", reason)
                    .setParameter("order", orderId)
                    .executeUpdate();
            remaining = remaining.subtract(amount);
        }
        return remaining.max(BigDecimal.ZERO);
    }
}

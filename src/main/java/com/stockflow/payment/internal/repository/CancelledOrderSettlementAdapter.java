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
                                    'ORDER_CANCELLED')
                            ON CONFLICT (payment_id) WHERE source = 'ORDER_CANCELLED' DO NOTHING""")
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

package com.stockflow.payment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL, as the order module's key tables: statements on rows only this follower writes.
 *
 * <p>G2-3: the money of an order is either a captured {@code payment.payment} (recorded before the ledger) or an
 * allocation of a customer transfer to the order (the ledger, G3-Q3). A refund is placed on either, newest money first,
 * never more of one than is left of it after the refunds it already carries (15 BR-05); an allocation that was
 * reversed carries no money and is skipped.</p>
 */
@Repository
class CancelledOrderSettlementAdapter implements CancelledOrderSettlement {

    /** Money of the order that a refund can be placed on: kind P (payment) or A (allocation), newest first. */
    private static final String MONEY_OF_ORDER = """
            SELECT 'P' AS kind, p.id, p.amount - COALESCE((SELECT SUM(r.amount) FROM payment.refund r
                                                            WHERE r.payment_id = p.id AND r.status <> 'FAILED'), 0),
                   p.captured_at AS at
              FROM payment.payment p
             WHERE p.order_id = :order AND p.status = 'CAPTURED' AND p.currency = :currency
            UNION ALL
            SELECT 'A', a.id, a.amount - COALESCE((SELECT SUM(r.amount) FROM payment.refund r
                                                   WHERE r.allocation_id = a.id AND r.status <> 'FAILED'), 0),
                   a.allocated_at
              FROM payment.transfer_allocation a
              JOIN payment.customer_transfer t ON t.id = a.transfer_id
             WHERE a.order_id = :order AND a.reverses_allocation_id IS NULL AND t.currency = :currency
               AND NOT EXISTS (SELECT 1 FROM payment.transfer_allocation x WHERE x.reverses_allocation_id = a.id)
             ORDER BY at DESC NULLS LAST, 2""";

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
        Object asked = entityManager.createNativeQuery("""
                        SELECT count(*) FROM payment.refund WHERE order_id = :order AND source = 'ORDER_CANCELLED'""")
                .setParameter("order", orderId)
                .getSingleResult();
        if (((Number) asked).longValue() > 0) {
            // An order is cancelled once: a redelivered OrderCancelled finds its refunds asked already. Looping again
            // would skip the money they used up and ask the older money for the same amount a second time (BR-05).
            return BigDecimal.ZERO;
        }
        BigDecimal remaining = refundable;
        for (Object[] row : moneyOf(orderId, currency)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal left = (BigDecimal) row[2];
            if (left.signum() <= 0) {
                continue;
            }
            BigDecimal amount = remaining.min(left);
            String column = "P".equals(row[0]) ? "payment_id" : "allocation_id";
            // One refund per payment / allocation of a cancelled order: a redelivered OrderCancelled asks once.
            int inserted = entityManager.createNativeQuery("""
                            INSERT INTO payment.refund (id, %1$s, amount, currency, reason, status, created_at, order_id,
                                                        source)
                            VALUES (:id, :money, :amount, :currency, :reason, 'PENDING', NOW(), :order,
                                    'ORDER_CANCELLED')
                            ON CONFLICT (%1$s) WHERE source = 'ORDER_CANCELLED' DO NOTHING""".formatted(column))
                    .setParameter("id", Identifiers.newId())
                    .setParameter("money", row[1])
                    .setParameter("amount", amount)
                    .setParameter("currency", currency)
                    .setParameter("reason", reason)
                    .setParameter("order", orderId)
                    .executeUpdate();
            if (inserted == 1) {
                remaining = remaining.subtract(amount);
            }
        }
        return remaining.max(BigDecimal.ZERO);
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
        BigDecimal remaining = refundable;
        for (Object[] row : moneyOf(orderId, currency)) {
            BigDecimal left = (BigDecimal) row[2];
            if (remaining.signum() <= 0) {
                break;
            }
            if (left.signum() <= 0) {
                continue;
            }
            BigDecimal amount = remaining.min(left);
            String column = "P".equals(row[0]) ? "payment_id" : "allocation_id";
            int inserted = entityManager.createNativeQuery("""
                            INSERT INTO payment.refund (id, %1$s, amount, currency, reason, status, created_at,
                                                        order_id, source, remainder_closure_id)
                            VALUES (:id, :money, :amount, :currency, :reason, 'PENDING', NOW(), :order,
                                    'ORDER_REMAINDER_CLOSED', :closure)
                            ON CONFLICT (%1$s, remainder_closure_id) WHERE source = 'ORDER_REMAINDER_CLOSED'
                            DO NOTHING""".formatted(column))
                    .setParameter("id", Identifiers.newId())
                    .setParameter("money", row[1])
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

    @SuppressWarnings("unchecked")
    private List<Object[]> moneyOf(UUID orderId, String currency) {
        return entityManager.createNativeQuery(MONEY_OF_ORDER)
                .setParameter("order", orderId)
                .setParameter("currency", currency)
                .getResultList();
    }
}

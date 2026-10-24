package com.stockflow.payment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.payment.internal.domain.ReturnRefunds;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL on {@code payment.refund}, as {@code CancelledOrderSettlementAdapter}: the money of an order a refund can
 * be placed on is a captured payment (recorded before the ledger) or an allocation of a real transfer to the order or
 * its receivables, newest first, with what is left of it after its refunds. Allocations of a return's credit entry
 * (RETURN_CREDIT) are left out: they are a credit, not money the customer paid.
 */
@Repository
class ReturnRefundsAdapter implements ReturnRefunds {

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
               AND t.channel <> 'RETURN_CREDIT'
               AND NOT EXISTS (SELECT 1 FROM payment.transfer_allocation x WHERE x.reverses_allocation_id = a.id)
             ORDER BY at DESC NULLS LAST, 2""";

    private final EntityManager entityManager;

    ReturnRefundsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Asked> requestReturnRefunds(UUID orderId, UUID returnRequestId, BigDecimal amount, String currency,
                                            String reason) {
        List<Asked> asked = new ArrayList<>();
        BigDecimal remaining = amount;
        for (Object[] row : moneyOf(orderId, currency)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal left = (BigDecimal) row[2];
            if (left.signum() <= 0) {
                continue;
            }
            BigDecimal part = remaining.min(left);
            String column = "P".equals(row[0]) ? "payment_id" : "allocation_id";
            UUID id = Identifiers.newId();
            // uk_refund_return_payment / uk_refund_return_allocation: once per money row and return.
            int inserted = entityManager.createNativeQuery("""
                            INSERT INTO payment.refund (id, %1$s, amount, currency, reason, status, created_at, order_id,
                                                        source, return_request_id)
                            VALUES (:id, :money, :amount, :currency, :reason, 'PENDING', NOW(), :order, 'RETURN',
                                    :return)
                            ON CONFLICT DO NOTHING""".formatted(column))
                    .setParameter("id", id)
                    .setParameter("money", row[1])
                    .setParameter("amount", part)
                    .setParameter("currency", currency)
                    .setParameter("reason", reason)
                    .setParameter("order", orderId)
                    .setParameter("return", returnRequestId)
                    .executeUpdate();
            if (inserted == 1) {
                asked.add(new Asked(id, part));
                remaining = remaining.subtract(part);
            }
        }
        return List.copyOf(asked);
    }

    @Override
    public boolean hasRefunds(UUID returnRequestId) {
        Object found = entityManager.createNativeQuery(
                        "SELECT EXISTS (SELECT 1 FROM payment.refund WHERE return_request_id = :return)")
                .setParameter("return", returnRequestId)
                .getSingleResult();
        return Boolean.TRUE.equals(found);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> moneyOf(UUID orderId, String currency) {
        return entityManager.createNativeQuery(MONEY_OF_ORDER)
                .setParameter("order", orderId)
                .setParameter("currency", currency)
                .getResultList();
    }
}

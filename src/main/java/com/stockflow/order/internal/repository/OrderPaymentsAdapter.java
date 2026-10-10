package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.OrderPayments;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Native SQL on a key table written only by the payment listener, as {@code ProductionRecordsAdapter}. */
@Repository
class OrderPaymentsAdapter implements OrderPayments {

    private final EntityManager entityManager;

    OrderPaymentsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean record(UUID paymentId, UUID orderId, BigDecimal amount, String currency, Instant capturedAt) {
        return entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_payment (payment_id, order_id, amount, currency, captured_at)
                        VALUES (:payment, :order, :amount, :currency, :at)
                        ON CONFLICT (payment_id) DO NOTHING""")
                .setParameter("payment", paymentId)
                .setParameter("order", orderId)
                .setParameter("amount", amount)
                .setParameter("currency", currency)
                .setParameter("at", capturedAt)
                .executeUpdate() == 1;
    }
}

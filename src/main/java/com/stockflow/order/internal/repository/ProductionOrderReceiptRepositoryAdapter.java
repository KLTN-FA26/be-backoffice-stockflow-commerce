package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionOrderReceiptRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProductionOrderReceiptRepository} over {@code ordering.production_order_receipt}, in SQL: a registration is
 * a handful of columns written twice at most, with no behaviour of its own to map.
 */
@Repository
class ProductionOrderReceiptRepositoryAdapter implements ProductionOrderReceiptRepository {

    private final EntityManager entityManager;

    ProductionOrderReceiptRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Registered> find(UUID productionOrderId, UUID receiptId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT receipt_number, status FROM ordering.production_order_receipt
                         WHERE production_order_id = :order AND goods_receipt_id = :receipt""")
                .setParameter("order", productionOrderId)
                .setParameter("receipt", receiptId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new Registered(productionOrderId, receiptId, (String) row[0],
                Status.valueOf((String) row[1])));
    }

    @Override
    public void open(UUID productionOrderId, UUID receiptId, String receiptNumber, Instant at) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id,
                                                                       receipt_number, status, opened_at)
                        VALUES (:order, :receipt, :number, 'DRAFT', :at)""")
                .setParameter("order", productionOrderId)
                .setParameter("receipt", receiptId)
                .setParameter("number", receiptNumber)
                .setParameter("at", at)
                .executeUpdate();
    }

    @Override
    public void settle(UUID productionOrderId, UUID receiptId, Status status, Instant at) {
        if (status == Status.DRAFT) {
            throw new IllegalArgumentException("A registered receipt is settled confirmed or cancelled");
        }
        int settled = entityManager.createNativeQuery("""
                        UPDATE ordering.production_order_receipt SET status = :status, settled_at = :at
                         WHERE production_order_id = :order AND goods_receipt_id = :receipt AND status = 'DRAFT'""")
                .setParameter("status", status.name())
                .setParameter("at", at)
                .setParameter("order", productionOrderId)
                .setParameter("receipt", receiptId)
                .executeUpdate();
        if (settled != 1) {
            // Callers settle only a registered draft; anything else is a fault here, not at commit in a trigger.
            throw new IllegalStateException("Receipt %s of production order %s is not a registered draft"
                    .formatted(receiptId, productionOrderId));
        }
    }

    @Override
    public List<String> drafts(UUID productionOrderId) {
        @SuppressWarnings("unchecked")
        List<String> numbers = entityManager.createNativeQuery("""
                        SELECT receipt_number FROM ordering.production_order_receipt
                         WHERE production_order_id = :order AND status = 'DRAFT' ORDER BY receipt_number""")
                .setParameter("order", productionOrderId)
                .getResultList();
        return numbers;
    }
}

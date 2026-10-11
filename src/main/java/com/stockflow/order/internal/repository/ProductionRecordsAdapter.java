package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionRecords;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL on two small tables of this module's own schema, written only by event listeners and read
 * by release; an entity per table would add mapping and nothing else.
 */
@Repository
class ProductionRecordsAdapter implements ProductionRecords {

    private final EntityManager entityManager;

    ProductionRecordsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<UUID> approvedSample(UUID designSnapshotId, String blankSku) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery("""
                        SELECT sample_request_id FROM ordering.approved_sample
                         WHERE design_snapshot_id = :design AND blank_sku = :blank
                         ORDER BY approved_at DESC""")
                .setParameter("design", designSnapshotId)
                .setParameter("blank", blankSku)
                .setMaxResults(1)
                .getResultList();
        return found.stream().findFirst();
    }

    @Override
    public boolean producedBefore(UUID designSnapshotId, UUID excludingOrderId) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM ordering.order_line_production p
                          JOIN ordering.order_line l ON l.id = p.order_line_id
                         WHERE l.design_snapshot_id = :design AND l.order_id <> :order""")
                .setParameter("design", designSnapshotId)
                .setParameter("order", excludingOrderId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public void recordSampleApproved(UUID sampleRequestId, UUID customerId, UUID designSnapshotId, String blankSku,
                                     Instant approvedAt) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.approved_sample
                               (sample_request_id, customer_id, design_snapshot_id, blank_sku, approved_at)
                        VALUES (:id, :customer, :design, :blank, :at)
                        ON CONFLICT (sample_request_id) DO NOTHING""")
                .setParameter("id", sampleRequestId)
                .setParameter("customer", customerId)
                .setParameter("design", designSnapshotId)
                .setParameter("blank", blankSku)
                .setParameter("at", approvedAt)
                .executeUpdate();
    }

    @Override
    public boolean recordCompleted(UUID productionOrderId, UUID orderLineId, int goodQuantity, Instant completedAt) {
        return entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_line_production
                               (production_order_id, order_line_id, good_quantity, completed_at)
                        VALUES (:po, :line, :good, :at)
                        ON CONFLICT (production_order_id) DO NOTHING""")
                .setParameter("po", productionOrderId)
                .setParameter("line", orderLineId)
                .setParameter("good", goodQuantity)
                .setParameter("at", completedAt)
                .executeUpdate() == 1;
    }

    @Override
    public Map<UUID, Integer> producedByLine(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT p.order_line_id, SUM(p.good_quantity)
                          FROM ordering.order_line_production p
                          JOIN ordering.order_line l ON l.id = p.order_line_id
                         WHERE l.order_id = :order GROUP BY p.order_line_id""")
                .setParameter("order", orderId)
                .getResultList();
        Map<UUID, Integer> produced = new HashMap<>();
        rows.forEach(row -> produced.put((UUID) row[0], ((Number) row[1]).intValue()));
        return produced;
    }
}

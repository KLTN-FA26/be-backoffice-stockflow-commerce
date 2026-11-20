package com.stockflow.procurement.internal.repository;

import com.stockflow.inventory.spi.ReceiptLineLots;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory's question of {@code inventory :: spi} for the carton label (SCRUM-473): the lot of each goods receipt line,
 * so a carton still being counted - no stock row yet - prints the lot it will be received under. A line's lot is fixed
 * when the line is written (the production order's code, kltn-docs 03 BR-03) and never changes. One query for them all.
 */
@Repository
class ReceiptLineLotsAdapter implements ReceiptLineLots {

    private final EntityManager entityManager;

    ReceiptLineLotsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, String> lotsOf(Collection<UUID> receiptLineIds) {
        List<UUID> wanted = receiptLineIds.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery("""
                        select l.id, l.lotNumber from GoodsReceiptLineJpaEntity l where l.id in :ids""", Object[].class)
                .setParameter("ids", wanted).getResultList();
        Map<UUID, String> lots = new HashMap<>();
        for (Object[] row : rows) {
            lots.put((UUID) row[0], (String) row[1]);
        }
        return lots;
    }
}

package com.stockflow.procurement.internal.repository;

import com.stockflow.inventory.spi.ReceiptLineReceipts;
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
 * Inventory's question of {@code inventory :: spi} for the carton list (#454): the receipt of each goods receipt line,
 * so a carton links to the receipt it arrived on. A line never moves to another receipt. One query for them all.
 */
@Repository
class ReceiptLineReceiptsAdapter implements ReceiptLineReceipts {

    private final EntityManager entityManager;

    ReceiptLineReceiptsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, Receipt> receiptsOf(Collection<UUID> receiptLineIds) {
        List<UUID> wanted = receiptLineIds.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery("""
                        select l.id, r.id, r.receiptNumber from GoodsReceiptLineJpaEntity l join l.receipt r
                         where l.id in :ids""", Object[].class)
                .setParameter("ids", wanted).getResultList();
        Map<UUID, Receipt> receipts = new HashMap<>();
        for (Object[] row : rows) {
            receipts.put((UUID) row[0], new Receipt((UUID) row[1], (String) row[2]));
        }
        return receipts;
    }
}

package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One read by primary key on this module's own table; no entity until the item screens need one. */
@Repository
class InventoryItemDirectoryAdapter implements InventoryItemDirectory {

    private final EntityManager entityManager;

    InventoryItemDirectoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<InventoryItemPolicy> policyOf(UUID inventoryItemId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT sku, lot_tracked, expiry_tracked, qc_required
                          FROM inventory.inventory_items WHERE id = :id""")
                .setParameter("id", inventoryItemId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new InventoryItemPolicy(inventoryItemId, (String) row[0],
                (Boolean) row[1], (Boolean) row[2], (Boolean) row[3]));
    }
}

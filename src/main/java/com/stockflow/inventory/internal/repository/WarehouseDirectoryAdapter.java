package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Interim read of {@code warehouse.warehouse.prefix}; see {@link LocationDirectoryAdapter}. */
@Repository
class WarehouseDirectoryAdapter implements WarehouseDirectory {

    private final EntityManager entityManager;

    WarehouseDirectoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<String> prefixOf(UUID warehouseId) {
        @SuppressWarnings("unchecked")
        List<String> found = entityManager.createNativeQuery("SELECT prefix FROM warehouse.warehouse WHERE id = :id")
                .setParameter("id", warehouseId)
                .getResultList();
        return found.stream().findFirst();
    }
}

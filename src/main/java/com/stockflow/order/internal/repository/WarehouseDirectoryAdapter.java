package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.WarehouseDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interim read of {@code warehouse.warehouse.prefix} (the code that survives contract C2), the same
 * stop-gap as inventory's {@code WarehouseDirectoryAdapter}, until {@code warehouse :: api} publishes a
 * warehouse lookup (SCRUM-89). Replace with the API call when it lands.
 */
@Repository("orderWarehouseDirectory")
class WarehouseDirectoryAdapter implements WarehouseDirectory {

    private final EntityManager entityManager;

    WarehouseDirectoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<String> codeOf(UUID warehouseId) {
        @SuppressWarnings("unchecked")
        List<String> found = entityManager.createNativeQuery("SELECT prefix FROM warehouse.warehouse WHERE id = :id")
                .setParameter("id", warehouseId)
                .getResultList();
        return found.stream().findFirst();
    }
}

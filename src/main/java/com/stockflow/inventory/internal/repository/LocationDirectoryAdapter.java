package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * Interim: reads {@code warehouse.storage_location} until {@code warehouse :: api} publishes
 * {@code findLocation} (SCRUM-89, PR #48). One existence probe on a unique column, no join — and
 * the ledger's foreign key would refuse an unknown code anyway; this only turns that refusal into
 * a 404 the client can act on. Replace with the API call when it lands.
 */
@Repository
class LocationDirectoryAdapter implements LocationDirectory {

    private final EntityManager entityManager;

    LocationDirectoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean exists(LocationId location) {
        return !entityManager.createNativeQuery(
                        "SELECT 1 FROM warehouse.storage_location WHERE location_code = :code")
                .setParameter("code", location.code())
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }
}

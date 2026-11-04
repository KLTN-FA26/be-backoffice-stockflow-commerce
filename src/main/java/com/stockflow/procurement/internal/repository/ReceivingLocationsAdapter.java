package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.ReceivingLocation;
import com.stockflow.procurement.internal.domain.ReceivingLocations;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interim: reads {@code warehouse.storage_location} and {@code warehouse.area} until
 * {@code warehouse :: api} publishes a location lookup (SCRUM-89, PR #48) — the same stop-gap as
 * inventory's {@code LocationDirectoryAdapter}. Read-only, no join with this module's tables; the
 * receipt triggers check the same facts again at write time. Replace with the API call when it lands.
 */
@Repository
class ReceivingLocationsAdapter implements ReceivingLocations {

    private static final String SELECT = """
            SELECT s.id, s.location_code, s.warehouse_id, a.type
              FROM warehouse.storage_location s
              LEFT JOIN warehouse.area a ON a.location_id = s.id
            """;

    private final EntityManager entityManager;

    ReceivingLocationsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ReceivingLocation> byCode(String code) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(SELECT + " WHERE s.location_code = :code")
                .setParameter("code", code)
                .getResultList();
        return rows.stream().findFirst().map(ReceivingLocationsAdapter::toLocation);
    }

    @Override
    public Map<UUID, ReceivingLocation> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(SELECT + " WHERE s.id IN (:ids)")
                .setParameter("ids", ids)
                .getResultList();
        return rows.stream().map(ReceivingLocationsAdapter::toLocation)
                .collect(Collectors.toMap(ReceivingLocation::id, Function.identity()));
    }

    @Override
    public boolean hasArea(UUID warehouseId, String areaType) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM warehouse.area
                         WHERE warehouse_id = :warehouse AND type = :type AND location_id IS NOT NULL
                           AND status = 'ACTIVE'""")
                .setParameter("warehouse", warehouseId)
                .setParameter("type", areaType)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    private static ReceivingLocation toLocation(Object[] row) {
        return new ReceivingLocation((UUID) row[0], (String) row[1], (UUID) row[2], (String) row[3]);
    }
}

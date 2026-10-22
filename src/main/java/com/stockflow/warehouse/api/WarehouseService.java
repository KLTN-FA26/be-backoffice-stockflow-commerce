package com.stockflow.warehouse.api;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the warehouse module - the only package other modules may import.
 *
 * <p>Three lookups and nothing else (issue #18 D6). {@code warehouse.storage_location} is the target of
 * foreign keys from procurement (goods receipt lines, QC inspections), inventory (stock items,
 * movements, adjustments, cycle counts) and fulfillment (pick lines, move tasks), and ADR-0007 has
 * each of them check a reference in its service before writing it, with a real error code rather
 * than a foreign-key violation. Receiving, QC, putaway, picking and move tasks call these to do so:
 * by id when they already hold one, by code when a person scanned or typed it.</p>
 *
 * <p>Administering the map - warehouses, zones, shelves, areas, boundaries - is used by this
 * module's own controllers only and lives on the internal {@code WarehouseLayoutService} and its
 * siblings.</p>
 *
 * <p>Both methods run in the caller's transaction when there is one. Neither locks: a location's
 * status can change right after the lookup, as it can after any read; what a caller must not do is
 * skip {@link StorageLocationView#usable()} and look only at the location's own status.</p>
 */
public interface WarehouseService {

    /**
     * @param locationCode as scanned or typed - {@code hn-a01-2-03} finds {@code HN-A01-2-03}.
     *                     A {@code NON_STORAGE} area has no location and is never found.
     * @return empty when no location has this code, or the code is blank
     */
    Optional<StorageLocationView> findLocation(String locationCode);

    /** @return empty when no location has this id */
    Optional<StorageLocationView> findLocation(UUID locationId);

    /**
     * The location of the blanks this warehouse has sent to one print subcontractor (decision Q7, SCRUM-441): one
     * per subcontractor and sending warehouse, code {@code <prefix>-SUB-<supplier code>}, created the first time it
     * is asked for and returned as it is afterwards. Not on the map, not pickable, not a putaway target.
     */
    StorageLocationView subcontractorLocation(UUID warehouseId, UUID supplierId, String supplierCode);

    /** The storage areas of {@code type} in a warehouse, by location code (the print shop finds its packing area). */
    java.util.List<StorageLocationView> areasOf(UUID warehouseId, StorageLocationView.AreaType type);

    /**
     * The locations among {@code locationCodes} a picker may take stock from right now: flagged pickable
     * ({@code is_pickable}, module 06 BR-12) and usable, i.e. their effective status is ACTIVE. Each maps to its
     * warehouse. A code that is unknown, not pickable or not usable is absent. Allocation (SCRUM-152) chooses bins
     * from these only, so an order is never allocated stock in a receiving area, a QC area or a blocked bin.
     */
    java.util.Map<String, UUID> pickableLocations(java.util.Collection<String> locationCodes);

    /** @return empty when no warehouse has this id; a purchase order receives into one (SCRUM-390) */
    Optional<WarehouseView> findWarehouse(UUID warehouseId);
}

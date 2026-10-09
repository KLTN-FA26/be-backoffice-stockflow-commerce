package com.stockflow.warehouse.api;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the warehouse module - the only package other modules may import.
 *
 * <p>Two lookups and nothing else (issue #18 D6). {@code warehouse.storage_location} is the target of
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
}

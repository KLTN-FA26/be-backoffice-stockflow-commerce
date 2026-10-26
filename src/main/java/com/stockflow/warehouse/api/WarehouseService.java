package com.stockflow.warehouse.api;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the warehouse module - the only package other modules may import.
 *
 * <p>Lookups (issue #18 D6), plus the one location this module creates on another module's behalf.
 * {@code warehouse.storage_location} is the target of foreign keys from procurement (goods receipt
 * lines, QC inspections), inventory (stock items, movements, adjustments, cycle counts) and
 * fulfillment (pick lines, move tasks), and ADR-0007 has
 * each of them check a reference in its service before writing it, with a real error code rather
 * than a foreign-key violation. Receiving, QC, putaway, picking and move tasks call these to do so:
 * by id when they already hold one, by code when a person scanned or typed it.</p>
 *
 * <p>A cycle count (SCRUM-162) asks two more: the shelves of a zone, to plan a count by zone, and locations by code
 * whatever their status, to tell bins from areas without dropping a blocked bin that still holds stock to count. A
 * subcontractor's location (created on first use) and the areas of a type serve the print shop.</p>
 *
 * <p>Administering the map - warehouses, zones, shelves, areas, boundaries - is used by this
 * module's own controllers only and lives on the internal {@code WarehouseLayoutService} and its
 * siblings.</p>
 *
 * <p>Every method runs in the caller's transaction when there is one. None locks: a location's
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

    /**
     * The own codes of the shelves in a zone ({@code A01}, not {@code HCM-A01}), sorted. A cycle count planned by
     * zone (SCRUM-162) counts the bins of these shelves: a zone is a group of shelves this module owns, while a
     * shelf code is part of every bin's location code ({@code <prefix>-<shelf>-<level>-<bin>}), so the count can
     * select stock by code prefix without asking for every bin.
     *
     * @return empty for an unknown zone, a zone of another warehouse, or one with no shelves - a caller planning in
     *         {@code warehouseId} must never be handed another warehouse's shelf codes
     */
    java.util.List<String> shelfCodesOf(UUID warehouseId, UUID zoneId);

    /**
     * The locations with these codes, whatever their status, in one query. Unlike {@link #pickableLocations} a
     * blocked or inactive location is returned: a cycle count must still count the stock sitting in a bin nobody
     * may pick from, and it asks here only which codes are bins and which are areas.
     *
     * @param locationCodes exact codes as stored (upper case)
     * @return one view per known code; an unknown code is absent
     */
    java.util.List<StorageLocationView> findLocations(java.util.Collection<String> locationCodes);

    /**
     * The bins a putaway of stock of {@code storageClass} may go to in a warehouse (SCRUM-92): bins flagged as putaway
     * targets whose bin, shelf and warehouse are all ACTIVE (the same rule as {@link StorageLocationView#usable()}), never an area, by location
     * code. Capacity and weight are the bin's own limits; null where none is set.
     *
     * @return empty for an unknown warehouse or when no bin qualifies
     */
    java.util.List<PutawayCandidate> putawayCandidates(UUID warehouseId, StorageLocationView.StorageClass storageClass);

    /**
     * One location judged by the rule of {@link #putawayCandidates}, for a bin the clerk chose by hand: one indexed
     * read instead of the warehouse's whole list.
     *
     * @return empty when {@code locationCode} is not a putaway candidate of {@code storageClass} in the warehouse
     */
    java.util.Optional<PutawayCandidate> putawayCandidate(UUID warehouseId,
                                                         StorageLocationView.StorageClass storageClass,
                                                         String locationCode);
}

package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * One storage location with the statuses its effective status is computed from (issue #18 D2), in
 * one query: the location, its warehouse, and for a bin its shelf.
 */
public interface LocationLookupRepository {

    /** Exact match; the caller upper-cases a scanned code first (every part is {@code [A-Z0-9]}). */
    Optional<LocationRow> findByLocationCode(String locationCode);

    Optional<LocationRow> findById(UUID locationId);

    java.util.List<LocationRow> findAreas(UUID warehouseId, com.stockflow.warehouse.internal.domain.AreaType type);

    /** The location codes of every area of {@code type}, in every warehouse, whatever their status. */
    java.util.List<String> findAreaLocationCodes(com.stockflow.warehouse.internal.domain.AreaType type);

    /** The locations among {@code locationCodes} flagged {@code is_pickable}, whatever their status. */
    java.util.List<LocationRow> findPickable(java.util.Collection<String> locationCodes);

    /** The locations with these exact codes, whatever their status or flags; unknown codes are absent. */
    java.util.List<LocationRow> findByLocationCodes(java.util.Collection<String> locationCodes);

    /** The own codes of the zone's shelves in that warehouse, sorted; empty for an unknown or foreign zone. */
    java.util.List<String> findShelfCodesInZone(UUID warehouseId, UUID zoneId);

    /** The warehouse itself, for other modules naming it on a document (a production order's receiving warehouse). */
    Optional<WarehouseHeader> findWarehouseHeader(UUID warehouseId);

    /** Every ACTIVE warehouse, by code (4B-7: those an order may ship from). */
    java.util.List<WarehouseHeader> findActiveWarehouseHeaders();

    /**
     * The bins of a warehouse a putaway may choose from: BIN locations flagged {@code is_putaway_target} whose warehouse, shelf
     * and own status are all ACTIVE (the {@code EffectiveStatus.ofBin} rule, in the WHERE clause so the database does
     * the filtering), by location code. The bin's size and its level's clear height (null when unset) and the weight
     * limit as stored (null when unset). A non-null
     * {@code locationCode} narrows the same rule to that one location.
     */
    java.util.List<PutawayCandidateRow> findPutawayCandidates(UUID warehouseId, String locationCode);

    record PutawayCandidateRow(UUID locationId, String locationCode, java.math.BigDecimal width,
                               java.math.BigDecimal length, java.math.BigDecimal usableHeight,
                               java.math.BigDecimal maxWeight) {
    }

    record WarehouseHeader(UUID id, String prefix, String name, String address, WarehouseStatus status) {
    }

    /**
     * @param shelfStatus the bin's shelf; {@code null} for an area, whose own status is the
     *                    location's (D8)
     * @param width        a bin's width; null for an area, as are {@code length} and {@code usableHeight}
     * @param usableHeight the clear height of a bin's level; null too on a legacy level declared without one
     */
    record LocationRow(UUID id, StorageLocationKind kind, UUID warehouseId, String locationCode,
                       LocationStatus status, WarehouseStatus warehouseStatus,
                       LocationStatus shelfStatus, com.stockflow.warehouse.internal.domain.AreaType areaType,
                       java.math.BigDecimal width, java.math.BigDecimal length,
                       java.math.BigDecimal usableHeight) {
    }
}

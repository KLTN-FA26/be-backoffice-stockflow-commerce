package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An area with its storage location flattened in. Every location field - {@code locationId} through
 * {@code putawayTarget} - is {@code null} for a {@code NON_STORAGE} area, which has no location, and
 * so is left out of the JSON altogether ({@code spring.jackson.default-property-inclusion: non_null}).
 */
@Schema(name = "Area",
        description = "An area with its storage location; the location fields are absent for NON_STORAGE")
public record AreaResponse(UUID id, UUID warehouseId, String code, AreaType type, String name,
                           FootprintResponse footprint, boolean obstacle, LocationStatus status, long version,
                           UUID locationId, String locationCode, StorageClass storageClass, Integer capacityUnits,
                           BigDecimal maxWeight, Boolean pickable, Boolean putawayTarget) {
}

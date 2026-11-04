package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.Area;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An area and its storage location, flattened like a bin's. Every location field is {@code null}
 * for a {@code NON_STORAGE} area, which has no location.
 */
public record AreaSummary(UUID id, UUID warehouseId, String code, AreaType type, String name, Footprint footprint,
                          boolean obstacle, LocationStatus status, long version, UUID locationId,
                          String locationCode, StorageClass storageClass, Integer capacityUnits,
                          BigDecimal maxWeight, Boolean pickable, Boolean putawayTarget) {

    static AreaSummary of(Area area) {
        StorageLocation l = area.location();
        return new AreaSummary(area.id(), area.warehouseId(), area.code(), area.type(), area.name(),
                area.footprint(), area.obstacle(), area.status(), area.version(),
                l == null ? null : l.id(), l == null ? null : l.locationCode(),
                l == null ? null : l.storageClass(), l == null ? null : l.capacityUnits(),
                l == null ? null : l.maxWeight(), l == null ? null : l.pickable(),
                l == null ? null : l.putawayTarget());
    }
}

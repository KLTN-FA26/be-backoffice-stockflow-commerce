package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * A storage location found by its code - what a scanner resolves to. Check {@code usable}, not
 * {@code status}: a bin can be {@code ACTIVE} itself on a shelf that is not.
 */
@Schema(name = "StorageLocation", description = "A storage location; check usable, not status")
public record StorageLocationResponse(UUID id, StorageLocationKind kind, UUID warehouseId, String locationCode,
                                      StorageClass storageClass, LocationStatus status,
                                      LocationStatus effectiveStatus, boolean usable) {
}

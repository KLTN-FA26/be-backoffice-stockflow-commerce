package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.AreaDetails;

import java.util.UUID;

/** The write requests of {@link AreaLayoutService}, carrying the domain's already-validated {@link AreaDetails}. */
public final class AreaCommands {

    private AreaCommands() {
    }

    /** No zone: an area belongs to none (the table has no {@code zone_id}). */
    public record CreateArea(UUID warehouseId, String code, AreaDetails details) {
    }

    /** No code: it is part of the area's location code. */
    public record UpdateArea(UUID areaId, AreaDetails details, long expectedVersion) {
    }
}

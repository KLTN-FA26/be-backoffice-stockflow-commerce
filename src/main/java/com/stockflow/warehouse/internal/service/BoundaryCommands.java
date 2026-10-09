package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.warehouse.internal.domain.Segment;

import java.util.UUID;

/** The write requests of {@link BoundaryLayoutService}. */
public final class BoundaryCommands {

    private BoundaryCommands() {
    }

    /** @param operationalStatus doors only; {@code null} for a wall */
    public record CreateBoundary(UUID warehouseId, BoundaryType type, Segment segment, boolean passable,
                                 DoorStatus operationalStatus) {
    }

    public record UpdateBoundary(UUID boundaryId, BoundaryType type, Segment segment, boolean passable,
                                 DoorStatus operationalStatus, long expectedVersion) {
    }
}

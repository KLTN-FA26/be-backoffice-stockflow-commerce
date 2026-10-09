package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.Boundary;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.warehouse.internal.domain.Segment;

import java.math.BigDecimal;
import java.util.UUID;

/** A wall or door as the application layer hands it out, flat like its row. */
public record BoundarySummary(UUID id, UUID warehouseId, BoundaryType type, BigDecimal startX, BigDecimal startY,
                              BigDecimal endX, BigDecimal endY, boolean passable, DoorStatus operationalStatus,
                              long version) {

    static BoundarySummary of(Boundary boundary) {
        Segment s = boundary.segment();
        return new BoundarySummary(boundary.id(), boundary.warehouseId(), boundary.type(), s.startX(), s.startY(),
                s.endX(), s.endY(), boundary.passable(), boundary.operationalStatus(), boundary.version());
    }
}

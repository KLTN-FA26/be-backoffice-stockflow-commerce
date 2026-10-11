package com.stockflow.warehouse.internal.controller;

import com.stockflow.warehouse.internal.controller.dto.BoundaryRequests;
import com.stockflow.warehouse.internal.controller.dto.BoundaryResponse;
import com.stockflow.warehouse.internal.domain.Segment;
import com.stockflow.warehouse.internal.service.BoundaryCommands;
import com.stockflow.warehouse.internal.service.BoundarySummary;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.UUID;

/**
 * Boundary requests to commands and summaries to responses. Building the {@link Segment} validates
 * it, so a zero-length wall is a {@code 400} before any transaction starts.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface BoundaryWebMapper {

    BoundaryResponse toResponse(BoundarySummary boundary);

    default BoundaryCommands.CreateBoundary toCommand(UUID warehouseId, BoundaryRequests.CreateBoundary r) {
        return new BoundaryCommands.CreateBoundary(warehouseId, r.type(),
                new Segment(r.startX(), r.startY(), r.endX(), r.endY()), r.passable(), r.operationalStatus());
    }

    default BoundaryCommands.UpdateBoundary toCommand(UUID boundaryId, BoundaryRequests.UpdateBoundary r) {
        return new BoundaryCommands.UpdateBoundary(boundaryId, r.type(),
                new Segment(r.startX(), r.startY(), r.endX(), r.endY()), r.passable(), r.operationalStatus(),
                r.version());
    }
}

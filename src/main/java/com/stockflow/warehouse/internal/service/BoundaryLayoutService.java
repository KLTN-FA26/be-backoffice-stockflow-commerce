package com.stockflow.warehouse.internal.service;

import java.util.UUID;

/**
 * Walls and doors of a warehouse map. Every write takes the warehouse row lock first (issue #18 D4),
 * so a boundary is never drawn past a frame that another transaction is shrinking (BR-06).
 */
public interface BoundaryLayoutService {

    /** Both ends inside the map (BR-06). Never checked against shelves or areas: walls run beside them. */
    BoundarySummary createBoundary(BoundaryCommands.CreateBoundary command);

    BoundarySummary updateBoundary(BoundaryCommands.UpdateBoundary command);

    /** A hard delete (issue #18 D11). */
    void deleteBoundary(UUID boundaryId);
}

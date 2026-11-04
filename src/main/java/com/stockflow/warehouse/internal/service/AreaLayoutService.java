package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.LocationStatus;

import java.util.UUID;

/**
 * Floor areas of a warehouse map and their storage locations. Its own interface for the same reason
 * as {@link ShelfLayoutService}: one controller calls it, and it shares nothing with the others but
 * the warehouse lock, which lives in the repository.
 *
 * <p>Every write takes the warehouse row lock first (issue #18 D4): an area is checked against the
 * map frame (BR-06) and against every shelf and area on the layout (BR-07), rules that span
 * aggregates.</p>
 */
public interface AreaLayoutService {

    /** Inside the map (BR-06), clear of every shelf and area still on the layout (BR-07). */
    AreaSummary createArea(AreaCommands.CreateArea command);

    /** Moving, turning or resizing re-checks BR-06/07; the type changes only as #18 D10 allows. */
    AreaSummary updateArea(AreaCommands.UpdateArea command);

    /** Writes the location's status too (#18 D8); back from {@code INACTIVE} needs the place free (D3). */
    AreaSummary changeAreaStatus(UUID areaId, LocationStatus status);
}

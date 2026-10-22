package com.stockflow.fulfillment.internal.controller.dto;

import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.service.LoadEstimates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An advisory load estimate (SCRUM-287 PR EST; design v2 §5). {@code vehicles} is the 3D viewer's {@code Layout}
 * contract (FE #71), the same shape a real load plan answers in; {@code unplaced} lists the boxes left out with the
 * planner's reason (TOO_LARGE, TOO_HEAVY, NO_VEHICLE_AVAILABLE, NO_SPACE, SEARCH_CUT_OFF). {@code missing} is always
 * empty: a line without size or weight refuses the estimate with 422 LOAD_ESTIMATE_PACKAGING_MISSING instead.
 * {@code status} is CURRENT, or SUPERSEDED once the source was estimated again.
 */
public record LoadEstimateResponse(UUID id, String sourceType, UUID sourceId, UUID warehouseId, String status,
                                   String selectionMode, List<UUID> vehicleTypeIds,
                                   List<LoadEstimates.EstimateLine> lines, List<LoadEstimates.MissingLine> missing,
                                   List<LoadEstimates.EstimateWarning> warnings, List<Layout> vehicles,
                                   List<Layout.Unplaced> unplaced, LoadEstimates.EstimateMeta meta, Instant createdAt,
                                   String createdBy) {
}

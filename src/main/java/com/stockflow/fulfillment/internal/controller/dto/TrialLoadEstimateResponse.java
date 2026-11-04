package com.stockflow.fulfillment.internal.controller.dto;

import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.service.LoadEstimates;

import java.util.List;
import java.util.UUID;

/**
 * A trial estimate (LP2-9): the viewer's fields of {@link LoadEstimateResponse} - {@code vehicles}, {@code unplaced},
 * {@code meta}, {@code selectionMode}, {@code vehicleTypeIds}, in the same shape - so the 3D view renders it as it
 * renders a stored one. It has no identity, source, packaging lines or timestamps: nothing was stored. Weights in it
 * are a placeholder (the sheet has none) and the payload was not checked.
 *
 * @param trial always true: this answer was not stored and cannot be fetched again
 */
public record TrialLoadEstimateResponse(boolean trial, String selectionMode, List<UUID> vehicleTypeIds,
                                        List<Layout> vehicles, List<Layout.UnplacedLine> unplaced,
                                        LoadEstimates.EstimateMeta meta) {
}

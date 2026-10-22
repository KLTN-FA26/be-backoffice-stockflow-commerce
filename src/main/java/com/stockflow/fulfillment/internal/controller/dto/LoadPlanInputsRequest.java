package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * What a load plan is calculated from, replaced whole (09 §4.2 step 1, 3): the shipments (whole, LP-5), the vehicle
 * types and the selection mode (null keeps the current one). A PLANNED plan goes back to DRAFT.
 */
public record LoadPlanInputsRequest(@NotNull Long expectedVersion,
                                    @NotNull @Size(max = 200) List<@NotNull UUID> shipmentIds,
                                    @NotNull @Size(max = 50) List<@NotNull UUID> vehicleTypeIds,
                                    @Size(max = 24) String selectionMode) {
}

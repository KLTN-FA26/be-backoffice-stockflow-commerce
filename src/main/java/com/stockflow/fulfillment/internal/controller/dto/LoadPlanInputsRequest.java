package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * What a load plan is calculated from, replaced whole (09 §4.2 step 1, 3): the shipments (whole, LP-5), the vehicle
 * types, the selection mode (null keeps the current one) and, for PRIORITY_FILL, the order lines ticked as filler
 * cargo ("hàng chèn"; RTM-LPGROUP; null keeps the plan's). A PLANNED plan goes back to DRAFT.
 */
public record LoadPlanInputsRequest(@NotNull Long expectedVersion,
                                    @Size(max = 200) List<@NotNull UUID> shipmentIds,
                                    @NotNull @Size(max = 50) List<@NotNull UUID> vehicleTypeIds,
                                    @Size(max = 24) String selectionMode,
                                    @Size(max = 3000) List<@NotNull UUID> fillOrderLineIds) {
}

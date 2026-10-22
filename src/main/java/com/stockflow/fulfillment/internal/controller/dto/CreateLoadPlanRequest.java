package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A new load plan (SCRUM-287 PR D; 09 §4.2 step 1). Idempotent by {@code requestId}. Shipments (whole, LP-5), vehicle
 * types and the selection mode ({@code SINGLE_TYPE} by default, or {@code FEWEST_VEHICLES}; LP-6) may be given now or
 * later with PUT inputs.
 */
public record CreateLoadPlanRequest(@NotNull UUID requestId,
                                    @NotNull UUID warehouseId,
                                    @Size(max = 200) List<@NotNull UUID> shipmentIds,
                                    @Size(max = 50) List<@NotNull UUID> vehicleTypeIds,
                                    @Size(max = 24) String selectionMode,
                                    @Size(max = 500) String note) {
}

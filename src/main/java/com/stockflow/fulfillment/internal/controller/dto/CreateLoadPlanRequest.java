package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A new load plan (SCRUM-287 PR D; 09 §4.2 step 1). Idempotent by {@code requestId}. Shipments (whole, LP-5), vehicle
 * types and the selection mode ({@code SINGLE_TYPE} by default, or {@code FEWEST_VEHICLES}; LP-6) may be given now or
 * later with PUT inputs.
 *
 * <p>SCRUM-287 PR T: {@code sourceType} TRANSFER plans one packed transfer order ({@code transferOrderIds}, at most one,
 * 09 §4.2 step 1) instead of shipments; ORDER (the default) plans shipments. Mixing them is 409
 * LOAD_PLAN_SOURCE_MISMATCH.</p>
 */
public record CreateLoadPlanRequest(@NotNull UUID requestId,
                                    @NotNull UUID warehouseId,
                                    @Size(max = 200) List<@NotNull UUID> shipmentIds,
                                    @Size(max = 50) List<@NotNull UUID> vehicleTypeIds,
                                    @Size(max = 24) String selectionMode,
                                    @Size(max = 500) String note,
                                    @Size(max = 16) String sourceType,
                                    @Size(max = 1) List<@NotNull UUID> transferOrderIds) {
}

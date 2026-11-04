package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Re-solve one vehicle (09 §4.2 step 3, "tính lại riêng xe đó") with its own parcels and, optionally, parcels taken
 * from the tray ({@code addPackageIds}; a kit part brings the rest of its kit).
 */
public record VehicleRecalculationRequest(@NotNull Long expectedVersion,
                                          @Size(max = 500) List<@NotNull UUID> addPackageIds) {
}

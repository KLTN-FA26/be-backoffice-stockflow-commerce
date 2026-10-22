package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A vehicle type of the catalogue: inner dimensions and ceiling-corner clearance (gussetMm) in mm, payload in kg; {@code preset} types are never deleted. */
public record VehicleTypeResponse(UUID vehicleTypeId, String code, String name, String kind, int innerLengthMm,
                                  int innerWidthMm, int innerHeightMm, int gussetMm, BigDecimal maxPayloadKg,
                                  int availableCount, BigDecimal rentalPrice, boolean preset, String status, long version) {
}

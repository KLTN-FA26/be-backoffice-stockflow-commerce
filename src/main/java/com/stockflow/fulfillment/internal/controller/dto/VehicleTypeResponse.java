package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A vehicle type of the catalogue: inner dimensions and gusset cube edge (gussetMm: two cubes at the front-end top corners, four corner-post cubes on a 45' box, 0 = none) in mm, the optional door opening in mm (doorWidthMm / doorHeightMm; null = no door limit), payload in kg; {@code preset} types are never deleted. */
public record VehicleTypeResponse(UUID vehicleTypeId, String code, String name, String kind, int innerLengthMm,
                                  int innerWidthMm, int innerHeightMm, int gussetMm, Integer doorWidthMm,
                                  Integer doorHeightMm, BigDecimal maxPayloadKg,
                                  int availableCount, BigDecimal rentalPrice, boolean preset, String status, long version) {
}

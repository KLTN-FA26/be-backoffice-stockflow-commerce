package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One load in the list (G4-2a).
 *
 * @param shipmentCount shipments on its manifest; {@code packageCount} parcels scanned onto the vehicle
 * @param codTotal      what the carrier collects for it: as labelled while loading, as fixed once dispatched
 */
public record LoadRowResponse(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                              String carrierName, String vehiclePlate, String driverName, int shipmentCount,
                              int packageCount, BigDecimal codTotal, Instant loadingStartedAt, Instant dispatchedAt,
                              long version) {
}

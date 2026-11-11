package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One load in the list (G4-2a).
 *
 * @param shipmentCount shipments on its manifest; {@code packageCount} parcels scanned onto the vehicle
 * @param loadPlanId    its plan (SCRUM-392), the plan's code and the vehicle's seq; absent only on a load of the
 *                      parcel shipping that was removed
 * @param waybillNo     the carrier's waybill number (3-2), once recorded
 */
public record LoadRowResponse(UUID loadId, String code, UUID warehouseId, String status, UUID carrierId,
                              String carrierName, String vehiclePlate, String driverName, int shipmentCount,
                              int packageCount, Instant loadingStartedAt, Instant dispatchedAt,
                              long version, UUID loadPlanId, String loadPlanCode, Integer vehicleSeq,
                              String waybillNo) {
}

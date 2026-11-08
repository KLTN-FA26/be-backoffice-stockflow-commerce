package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A load plan's vehicle booked with a carrier outside the system (kltn-docs 09 §4.2 step 6, SCRUM-392). The plate, the
 * driver and the carrier's waybill number may be known now or only later.
 *
 * @param carrierId    a declared, active carrier
 * @param vehiclePlate the booked vehicle's plate, if known
 * @param waybillNo    the carrier's waybill number, if it gave one (3-2); left out, the number the load has is kept
 *                     while the carrier stays the same
 * @param pickupAt     API_BOOKING carrier (3-4): when the driver should pick up; left out, as soon as possible
 * @param stopShipmentIds API_BOOKING carrier: the load's shipments in the order they are delivered (E26); left out,
 *                     the manifest's order
 */
public record BookingRequest(@NotNull UUID carrierId,
                             @Size(max = 20) String vehiclePlate,
                             @Size(max = 120) String driverName,
                             @Size(max = 32) String driverPhone,
                             @Size(max = 64) String waybillNo,
                             java.time.Instant pickupAt,
                             @Size(max = 200) java.util.List<@NotNull UUID> stopShipmentIds) {
}

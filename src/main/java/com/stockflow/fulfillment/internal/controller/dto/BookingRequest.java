package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A load plan's vehicle booked with a carrier outside the system (kltn-docs 09 §4.2 step 5, SCRUM-392). The plate and
 * the driver may be known now or only at the signature.
 *
 * @param carrierId    a declared, active carrier
 * @param vehiclePlate the booked vehicle's plate, if known
 */
public record BookingRequest(@NotNull UUID carrierId,
                             @Size(max = 20) String vehiclePlate,
                             @Size(max = 120) String driverName,
                             @Size(max = 32) String driverPhone) {
}

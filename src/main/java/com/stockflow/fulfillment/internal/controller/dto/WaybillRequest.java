package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The carrier's waybill number for a load, typed (kltn-docs 09 §4.4 "(3) Nhập tay"), with the vehicle's plate and
 * driver when known.
 *
 * @param waybillNo    1-64 printable characters, no spaces; unique within the carrier among loads not cancelled
 * @param vehiclePlate left out: the load's plate is kept
 * @param reason       required once the vehicle left the warehouse: the change is then a correction (BR-24)
 */
public record WaybillRequest(@NotBlank @Size(max = 64) String waybillNo,
                             @Size(max = 20) String vehiclePlate,
                             @Size(max = 120) String driverName,
                             @Size(max = 32) String driverPhone,
                             @Size(max = 500) String reason) {
}

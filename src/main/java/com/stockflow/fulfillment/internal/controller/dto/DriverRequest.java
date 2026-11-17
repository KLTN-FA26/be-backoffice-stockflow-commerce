package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The driver of a booked or loading vehicle, typed by the dispatcher (WF4-DRIVER; kltn-docs 09 §4.2 steps 6 and 8):
 * the way in for a load booked through a carrier's API, whose waybill number cannot be typed. The driver alone: the
 * waybill number is never touched.
 *
 * @param driverName  the driver who will sign the manifest
 * @param driverPhone left out: the phone the load has is kept
 */
public record DriverRequest(@NotBlank @Size(max = 120) String driverName,
                            @Size(max = 32) String driverPhone) {
}

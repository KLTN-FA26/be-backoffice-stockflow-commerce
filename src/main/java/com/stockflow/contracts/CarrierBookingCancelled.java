package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>fulfillment</b> module when a carrier booked through its API cancels the order of a load before
 * the pickup - no driver took it, or the driver gave it back for good (kltn-docs 09 §4.5: "Đơn bị huỷ (trước khi lấy
 * hàng) → chuyến về Planned, xoá thông tin đặt xe, báo Dispatch Staff đặt lại"). The load is PLANNED again; the
 * <b>notification</b> module tells the warehouse's dispatchers to book it again.
 *
 * <p>Once per load and order: the event that cancels the booking is applied once. It carries codes only - no driver's
 * name or phone, nothing the carrier typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param carrierCode    the carrier's code, as configured
 * @param carrierOrderId the carrier's order id, which was the load's waybill number
 */
public record CarrierBookingCancelled(UUID eventId, UUID loadId, String loadCode, UUID warehouseId, String carrierCode,
                                      String carrierOrderId, Instant cancelledAt) {
}

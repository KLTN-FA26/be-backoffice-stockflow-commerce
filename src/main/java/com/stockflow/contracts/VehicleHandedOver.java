package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>fulfillment</b> module when a vehicle carrying goods of an order is signed for and leaves the
 * warehouse - once per vehicle and order (kltn-docs 09 §4.2 step 8; 17 BR-27; 00 §6 "Đơn đã bàn giao vận chuyển"). An
 * order on two vehicles hears twice, each with its own vehicle. The <b>notification</b> module tells the customer in
 * the portal's inbox, not by e-mail (E23).
 *
 * <p>What the customer is shown and nothing more: the carrier's name, the waybill number and the plate. Never the
 * driver's name or phone. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param waybillNo    the carrier's waybill number of the vehicle
 * @param vehiclePlate the plate on the signed manifest
 */
public record VehicleHandedOver(UUID eventId, UUID loadId, String loadCode, UUID warehouseId, UUID orderId,
                                String orderNumber, UUID customerId, String carrierName, String waybillNo,
                                String vehiclePlate, Instant handedOverAt) {
}

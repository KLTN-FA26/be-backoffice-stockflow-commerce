package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the internal package label prints (kltn-docs 08 step 6): no carrier is called, the front end renders it.
 *
 * @param packageNo    n of {@code packageCount} in its shipment
 * @param provisional  the package is not in a shipment yet: n/N and the COD amount count the sealed packages that would
 *                     ship together now, and change if more are sealed
 * @param codAmount    what the carrier collects for the shipment (09 BR-04: the balance of a deposit order whose carrier
 *                     collects it), 0 otherwise; as of now, fixed at the handover
 * @param returnAddress the warehouse's address, where the carrier returns what it cannot deliver (09 Input)
 */
public record PackageLabelResponse(String labelCode, UUID packageId, String orderNumber, UUID shipmentId,
                                   String shipmentCode, int packageNo, int packageCount, boolean provisional,
                                   BigDecimal codAmount, String currency, Recipient recipient,
                                   String returnWarehouseName, String returnAddress, String packageType,
                                   BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                                   BigDecimal dimWeightKg, boolean fragile, boolean rotatable,
                                   List<PackageResponse.Content> contents) {

    public record Recipient(String name, String phone, String line1, String line2, String wardName,
                            String provinceName, String countryCode, String postalCode) { }
}

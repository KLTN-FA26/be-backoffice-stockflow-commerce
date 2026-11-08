package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the internal package label prints (kltn-docs 08 step 6): no carrier is called, the front end renders it. It
 * carries no amount: nothing is collected on delivery (09 BR-08).
 *
 * @param packageNo    n of {@code packageCount} in its shipment
 * @param provisional  the package is not in a shipment yet: n/N counts the sealed packages that would ship together
 *                     now, and changes if more are sealed
 * @param returnAddress the warehouse's address, where the carrier returns what it cannot deliver (09 Input)
 * @param kit          the kit it is a part of (SCRUM-287 LP2-6): the label prints "Bộ partNo/partCount"; absent for an
 *                     ordinary package
 */
public record PackageLabelResponse(String labelCode, UUID packageId, String orderNumber, UUID shipmentId,
                                   String shipmentCode, int packageNo, int packageCount, boolean provisional,
                                   Recipient recipient,
                                   String returnWarehouseName, String returnAddress, String packageType,
                                   BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                                   BigDecimal dimWeightKg, boolean fragile, boolean rotatable,
                                   List<PackageResponse.Content> contents,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) KitPartResponse kit) {

    public record Recipient(String name, String phone, String line1, String line2, String wardName,
                            String provinceName, String countryCode, String postalCode) { }
}

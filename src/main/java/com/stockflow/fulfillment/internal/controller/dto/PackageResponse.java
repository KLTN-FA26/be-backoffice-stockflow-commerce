package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A package (parcel) at the packing station.
 *
 * @param labelCode  {@code PKG-<order number>-<n>}: on the label, what loading scans
 * @param status     {@code OPEN}, {@code SEALED} or {@code VOIDED}
 * @param shipmentId set once packing completed it into a shipment
 * @param rotatableOverridden the packer set the flag against what its SKUs say (E13); likewise {@code fragileOverridden}
 * @param kit        the kit it is a part of (SCRUM-287 LP2-6); absent for an ordinary package
 * @param kitOverrideReason KIT-FORCE: why this ordinary package holds an item that ships as several boxes; absent
 *                   otherwise
 * @param labelOutdated the parcel's label no longer matches it (it moved to another shipment or its n/N changed):
 *                   scanning it onto a load is 409 LABEL_OUTDATED until the label is reprinted
 * @param labelOutdatedAt since when; null when {@code labelOutdated} is false
 */
public record PackageResponse(UUID packageId, int packageNo, String labelCode, String packageType, String status,
                              UUID shipmentId, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                              BigDecimal heightCm, BigDecimal dimWeightKg, boolean rotatable,
                              boolean rotatableOverridden, boolean fragile, boolean fragileOverridden,
                              Instant sealedAt, List<Content> contents,
                              @JsonInclude(JsonInclude.Include.NON_NULL) KitPartResponse kit,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String kitOverrideReason,
                              boolean labelOutdated, Instant labelOutdatedAt) {

    /** Units of one order line in it, per lot and serial as scanned. */
    public record Content(UUID orderLineId, String sku, String lotNumber, String serialNumber, int quantity) { }
}

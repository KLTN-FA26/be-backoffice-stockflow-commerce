package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The handover manifest to print (kltn-docs 09 §3 Output "Biên bản bàn giao", §4.1 step 3): the warehouse, the carrier
 * and its vehicle, every shipment with its recipient, parcels and COD (BR-04), and the totals both sides sign for.
 *
 * @param codChangedSinceLabel per shipment: the COD fixed at the handover differs from its label's (money arrived
 *                             meanwhile) - the manifest's figure is the one collected
 */
public record ManifestResponse(String loadCode, String status, String warehouseName, String warehouseAddress,
                               String carrierCode, String carrierName, String carrierContactName,
                               String carrierContactPhone, String vehiclePlate, String driverName, String driverPhone,
                               String carrierRepresentative, String signatureRef, String handoverNote,
                               Instant dispatchedAt, String dispatchedByName, List<Shipment> shipments,
                               int shipmentCount, int packageCount, BigDecimal totalWeightKg, BigDecimal codTotal,
                               String currency, Instant printedAt) {

    public record Shipment(String shipmentCode, String orderNumber, String recipientName, String recipientPhone,
                           String deliveryAddress, int packageCount, BigDecimal weightKg, BigDecimal codAmount,
                           boolean codChangedSinceLabel, List<Parcel> packages) {
    }

    /** @param kit the kit it is a part of (SCRUM-287 LP2-6): printed "Bộ partNo/partCount"; absent otherwise */
    public record Parcel(String labelCode, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                         BigDecimal heightCm, boolean loaded,
                         @JsonInclude(JsonInclude.Include.NON_NULL) KitPartResponse kit) {
    }
}

package com.stockflow.fulfillment.internal.controller.dto;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The handover manifest to print (kltn-docs 09 §3 Output "Biên bản bàn giao", §4.2 step 5): the warehouse, the carrier
 * and its vehicle, every shipment with its recipient and parcels, and the totals both sides sign for. It is per vehicle
 * (BR-15): it lists only the parcels its plan placed on the vehicle ({@code loadPlanCode}, {@code vehicleSeq},
 * {@code vehicleTypeCode}). Nothing is collected on delivery (09 BR-08).
 */
public record ManifestResponse(String loadCode, String status, String warehouseName, String warehouseAddress,
                               String carrierCode, String carrierName, String carrierContactName,
                               String carrierContactPhone, String vehiclePlate, String driverName, String driverPhone,
                               String carrierRepresentative, String signatureRef, String handoverNote,
                               Instant dispatchedAt, String dispatchedByName, List<Shipment> shipments,
                               int shipmentCount, int packageCount, BigDecimal totalWeightKg, Instant printedAt,
                               String loadPlanCode, Integer vehicleSeq, String vehicleTypeCode, String waybillNo) {

    public record Shipment(String shipmentCode, String orderNumber, String recipientName, String recipientPhone,
                           String deliveryAddress, int packageCount, BigDecimal weightKg, List<Parcel> packages) {
    }

    public record Parcel(String labelCode, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                         BigDecimal heightCm, boolean loaded) {
    }
}

package com.stockflow.fulfillment.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A load with its manifest: the shipments on it, their parcels and which are on the vehicle (kltn-docs 09 §4.1).
 *
 * @param expectedPackageCount the sealed parcels of the shipments on the manifest
 * @param loadedPackageCount   of those, scanned onto the vehicle
 * @param version              send it back with the handover
 */
public record LoadResponse(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                           String carrierCode, String carrierName, String vehiclePlate, String driverName,
                           String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                           UUID dispatchedBy, String dispatchedByName, String carrierRepresentative,
                           String signatureRef, String handoverNote, Instant cancelledAt, String cancelReason,
                           int shipmentCount, int expectedPackageCount, int loadedPackageCount, BigDecimal codTotal,
                           String currency, long version, List<Shipment> shipments) {

    /**
     * @param labelCodAmount what the shipment's label says the carrier collects
     * @param codAmount      what the order fixed at the handover; null before it
     */
    public record Shipment(UUID shipmentId, String code, UUID orderId, String orderNumber, String status,
                           int packageCount, int loadedCount, BigDecimal labelCodAmount, BigDecimal codAmount,
                           List<Parcel> packages) {
    }

    public record Parcel(UUID packageId, String labelCode, BigDecimal weightKg, boolean loaded, Instant loadedAt,
                         UUID loadedBy) {
    }
}

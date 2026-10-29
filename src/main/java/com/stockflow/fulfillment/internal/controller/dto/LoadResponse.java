package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param loadPlanId           FULL_TRUCKLOAD only (SCRUM-392): the plan this load is a vehicle of, with its code, the
 *                             vehicle's seq and type code; the parcels listed are those the plan placed on it
 * @param bookedAt             when the vehicle was booked (VEHICLE_BOOKED and later); null before or after a booking
 *                             cancellation, whose time and reason are kept in {@code bookingCancelledAt/Reason}
 * @param pendingUnloads       parcels of cancelled orders still on the vehicle: unload them (POST .../unloads) before
 *                             the handover (409 LOAD_UNLOAD_PENDING)
 * @param cancelReasonCode     XE_HONG, SAI_XE, KIEN_KHONG_VUA or KHAC when it was called off under control (SCRUM-474);
 *                             {@code cancelReason} is then the note; absent for any other cancellation
 * @param replacementLoadId    on a vehicle called off under control: the live load (PLANNED, same plan vehicle and
 *                             layout) that took its place, with {@code replacementLoadCode}; absent when nothing replaced
 *                             it (it had nothing left to carry) and on every other load
 * @param carrierBookingToCancel CANCELLED after it was booked (the plan reopened or cancelled, or the system called it
 *                             off with nothing left to carry - {@code cancelReason} "auto-cancelled: ..."): the
 *                             booking made with the carrier outside the system still exists - cancel it there
 */
public record LoadResponse(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                           String carrierCode, String carrierName, String vehiclePlate, String driverName,
                           String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                           UUID dispatchedBy, String dispatchedByName, String carrierRepresentative,
                           String signatureRef, String handoverNote, Instant cancelledAt, String cancelReason,
                           int shipmentCount, int expectedPackageCount, int loadedPackageCount, BigDecimal codTotal,
                           String currency, long version, List<Shipment> shipments, UUID loadPlanId,
                           String loadPlanCode, Integer vehicleSeq, String vehicleTypeCode, Instant bookedAt,
                           UUID bookedBy, Instant bookingCancelledAt, String bookingCancelReason,
                           List<PendingUnload> pendingUnloads, boolean carrierBookingToCancel,
                           String cancelReasonCode,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID replacementLoadId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String replacementLoadCode) {

    /**
     * A parcel still to take off the vehicle: its order was cancelled (SCRUM-392) or the vehicle was called off under
     * control (SCRUM-474; then {@code reasonCode} is set). Locked until scanned off. On a transfer plan's vehicle
     * {@code shipmentId} is absent and {@code transferOrderId} set ({@code shipmentCode} is its number).
     */
    public record PendingUnload(UUID packageId, String labelCode,
                                @JsonInclude(JsonInclude.Include.NON_NULL) UUID shipmentId, String shipmentCode,
                                String reason, Instant requestedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode,
                                @JsonInclude(JsonInclude.Include.NON_NULL) UUID transferOrderId) {
    }

    /**
     * @param taskId         the fulfillment task it was packed in: reprint its label at
     *                       {@code GET /fulfillment/tasks/{taskId}/packages/{packageId}/label} (after LABEL_OUTDATED)
     * @param labelCodAmount what the shipment's label says the carrier collects
     * @param codAmount      what the order fixed at the handover; null before it
     * @param currency       of both amounts
     * @param transferOrderId SCRUM-287 PR T: the transfer order on a TRANSFER plan's vehicle (then shipmentId, orderId
     *                       and taskId are null, code is the transfer's number); absent otherwise
     */
    public record Shipment(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, String status,
                           int packageCount, int loadedCount, BigDecimal labelCodAmount, BigDecimal codAmount,
                           String currency, List<Parcel> packages,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID transferOrderId) {
    }

    /**
     * @param kit             the kit it is a part of (SCRUM-287 LP2-6); absent for an ordinary parcel
     * @param labelOutdated   its label no longer matches the parcel (moved to another shipment, or its n/N changed):
     *                        scanning it is 409 LABEL_OUTDATED until the label is reprinted
     * @param labelOutdatedAt since when; null when {@code labelOutdated} is false
     */
    public record Parcel(UUID packageId, String labelCode, BigDecimal weightKg, boolean loaded, Instant loadedAt,
                         UUID loadedBy, @JsonInclude(JsonInclude.Include.NON_NULL) KitPartResponse kit,
                         boolean labelOutdated, Instant labelOutdatedAt) {
    }
}

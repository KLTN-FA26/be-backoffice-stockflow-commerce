package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A load with its manifest: the shipments on it, their parcels and which are on the vehicle (kltn-docs 09 §4.2).
 *
 * @param warehouseName        the load's warehouse (the dispatch warehouse the booking slip names); null only if the
 *                             warehouse can no longer be read
 * @param expectedPackageCount the sealed parcels of the shipments on the manifest
 * @param loadedPackageCount   of those, scanned onto the vehicle
 * @param version              send it back with the handover
 * @param loadPlanId           the plan this load is a vehicle of (SCRUM-392), with its code, the vehicle's seq and type
 *                             code; the parcels listed are those the plan placed on it. Absent only on a load of the
 *                             parcel shipping that was removed
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
 * @param loadingStartedByName the display name of {@code loadingStartedBy}; null before loading started or if unknown
 * @param bookedByName         the display name of {@code bookedBy}; null while not booked or if unknown
 * @param warehouseCode        the code (prefix) of {@code warehouseId}; null only if the warehouse can no longer be read
 * @param driverReleasedAt     last applied carrier report that the driver gave the trip back; null otherwise
 */
public record LoadResponse(UUID loadId, String code, UUID warehouseId, String warehouseName, String status,
                           UUID carrierId,
                           String carrierCode, String carrierName, String vehiclePlate, String driverName,
                           String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                           UUID dispatchedBy, String dispatchedByName, String carrierRepresentative,
                           String signatureRef, String handoverNote, Instant cancelledAt, String cancelReason,
                           int shipmentCount, int expectedPackageCount, int loadedPackageCount, long version,
                           List<Shipment> shipments, UUID loadPlanId,
                           String loadPlanCode, Integer vehicleSeq, String vehicleTypeCode, Instant bookedAt,
                           UUID bookedBy, Instant bookingCancelledAt, String bookingCancelReason,
                           List<PendingUnload> pendingUnloads, boolean carrierBookingToCancel,
                           String cancelReasonCode,
                           @JsonInclude(JsonInclude.Include.NON_NULL) UUID replacementLoadId,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String replacementLoadCode,
                           String waybillNo, String carrierConnectionType,
                           @JsonInclude(JsonInclude.Include.NON_NULL) CarrierBooking carrierBooking,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<HandoverWarning> handoverWarnings,
                           String loadingStartedByName, String bookedByName, String warehouseCode,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Instant driverReleasedAt) {

    /**
     * 4B-8 (kltn-docs 08 BR-11, 09 BR-18): an order on the vehicle whose goods may not leave yet, answered when loading
     * starts - the handover refuses the vehicle until it may. {@code reasonCodes} lists every reason (money owed and a
     * window not yet open together), {@code reasonCode} is the first, kept for callers that read one.
     */
    public record HandoverWarning(UUID orderId, String orderNumber, String reasonCode, List<String> reasonCodes,
                                  java.math.BigDecimal amountDue, String currency,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Integer deliveryBatchSeq,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) java.time.LocalDate windowStart) {
    }

    /**
     * The load's latest order at an API_BOOKING carrier (3-4): REQUESTED, BOOKED, FAILED, CANCELLED or CANCEL_FAILED
     * (the carrier would not cancel it after the load was called off - settle it with the carrier).
     */
    public record CarrierBooking(String status, String carrierOrderId, String trackingLink,
                                 java.time.Instant requestedAt, java.time.Instant bookedAt, String lastError) {
    }

    /**
     * A parcel still to take off the vehicle: its order was cancelled (SCRUM-392) or the vehicle was called off under
     * control (SCRUM-474; then {@code reasonCode} is set). Locked until scanned off.
     */
    public record PendingUnload(UUID packageId, String labelCode,
                                @JsonInclude(JsonInclude.Include.NON_NULL) UUID shipmentId, String shipmentCode,
                                String reason, Instant requestedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode) {
    }

    /** @param taskId the fulfillment task its cartons were checked and labelled in */
    public record Shipment(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, String status,
                           int packageCount, int loadedCount, List<Parcel> packages) {
    }

    /**
     * @param lpn          the carton's LPN, the barcode on its shipping label (08 §4.3 step 6); a scan may carry it or
     *                     labelCode
     * @param loadedByName the display name of {@code loadedBy}, who scanned it onto the vehicle; null while not loaded
     *                     or if unknown
     */
    public record Parcel(UUID packageId, String labelCode, String lpn, BigDecimal weightKg, boolean loaded,
                         Instant loadedAt, UUID loadedBy, String loadedByName) {
    }
}

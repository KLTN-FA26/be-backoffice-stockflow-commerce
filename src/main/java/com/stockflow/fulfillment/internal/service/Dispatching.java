package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Loading and handover (SCRUM-288/292/293, SCRUM-392; kltn-docs 09 §4.2): a load is one vehicle of a confirmed load
 * plan at one warehouse - every order ships as a full truckload (09 BR-08, 2026-11-05). The dispatcher books the
 * vehicle, starts loading when it arrives, scans each parcel the plan placed on it, and both sides sign the manifest.
 * The signature hands the shipments over - each with all its parcels (BR-03) - and tells the order in the same
 * transaction, so the order turns PARTIALLY_FULFILLED or SHIPPED and a prepaid order not fully paid is refused (G3-Q4).
 * Nothing is collected on delivery: there is no COD.
 *
 * <p>Inside {@code internal}: only this module's web layer calls it. Every write locks the load's row first, then the
 * shipments' rows in id order, then (through {@code OrderService}) the orders': two dispatchers on one load queue up,
 * and the second sees what the first did. Every call is limited to the caller's warehouses ({@code OUT_OF_DATA_SCOPE}).
 * </p>
 */
public interface Dispatching {

    /**
     * @param loadPlanId the load's plan (SCRUM-392), with its code and the vehicle's seq; null only for a load of the
     *                   parcel shipping that was removed
     */
    record LoadRow(UUID loadId, String code, UUID warehouseId, String status, UUID carrierId, String carrierName,
                   String vehiclePlate, String driverName, int shipmentCount, int packageCount,
                   Instant loadingStartedAt, Instant dispatchedAt, long version, UUID loadPlanId, String loadPlanCode,
                   Integer vehicleSeq, String waybillNo) { }

    /**
     * A parcel still to take off a vehicle (SCRUM-392: its order was cancelled; SCRUM-474: its vehicle was called off
     * under control). {@code reasonCode} only for the second kind.
     */
    record PendingUnload(UUID packageId, String labelCode, UUID shipmentId, String shipmentCode, String reason,
                         Instant requestedAt, String reasonCode) { }

    record LoadParcel(UUID packageId, String labelCode, BigDecimal weightKg, boolean loaded, Instant loadedAt,
                      UUID loadedBy) { }

    /**
     * A shipment on the manifest.
     *
     * @param taskId the fulfillment task it was checked in: where its cartons' labels are reprinted
     */
    record LoadShipment(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, String status,
                        int packageCount, int loadedCount, List<LoadParcel> packages) { }

    record LoadDetail(UUID loadId, String code, UUID warehouseId, String status, UUID carrierId,
                      String carrierCode, String carrierName, String vehiclePlate, String driverName,
                      String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                      UUID dispatchedBy, String dispatchedByName, String carrierRepresentative, String signatureRef,
                      String handoverNote, Instant cancelledAt, String cancelReason, int shipmentCount,
                      int expectedPackageCount, int loadedPackageCount, long version, List<LoadShipment> shipments, UUID loadPlanId, String loadPlanCode,
                      Integer vehicleSeq, String vehicleTypeCode, Instant bookedAt, UUID bookedBy,
                      Instant bookingCancelledAt, String bookingCancelReason, List<PendingUnload> pendingUnloads,
                      boolean carrierBookingToCancel, String cancelReasonCode, UUID replacementLoadId,
                      String replacementLoadCode, String waybillNo, String carrierConnectionType,
                      CarrierBookingView carrierBooking, List<HandoverWarning> handoverWarnings) {

        public LoadDetail {
            handoverWarnings = handoverWarnings == null ? List.of() : List.copyOf(handoverWarnings);
        }

        /** Without handover warnings: every answer but the start of loading. */
        public LoadDetail(UUID loadId, String code, UUID warehouseId, String status, UUID carrierId,
                          String carrierCode, String carrierName, String vehiclePlate, String driverName,
                          String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                          UUID dispatchedBy, String dispatchedByName, String carrierRepresentative, String signatureRef,
                          String handoverNote, Instant cancelledAt, String cancelReason, int shipmentCount,
                          int expectedPackageCount, int loadedPackageCount, long version, List<LoadShipment> shipments,
                          UUID loadPlanId, String loadPlanCode, Integer vehicleSeq, String vehicleTypeCode,
                          Instant bookedAt, UUID bookedBy, Instant bookingCancelledAt, String bookingCancelReason,
                          List<PendingUnload> pendingUnloads, boolean carrierBookingToCancel, String cancelReasonCode,
                          UUID replacementLoadId, String replacementLoadCode, String waybillNo,
                          String carrierConnectionType, CarrierBookingView carrierBooking) {
            this(loadId, code, warehouseId, status, carrierId, carrierCode, carrierName, vehiclePlate, driverName,
                    driverPhone, loadingStartedAt, loadingStartedBy, dispatchedAt, dispatchedBy, dispatchedByName,
                    carrierRepresentative, signatureRef, handoverNote, cancelledAt, cancelReason, shipmentCount,
                    expectedPackageCount, loadedPackageCount, version, shipments, loadPlanId, loadPlanCode, vehicleSeq,
                    vehicleTypeCode, bookedAt, bookedBy, bookingCancelledAt, bookingCancelReason, pendingUnloads,
                    carrierBookingToCancel, cancelReasonCode, replacementLoadId, replacementLoadCode, waybillNo,
                    carrierConnectionType, carrierBooking, List.of());
        }

        /** The same load with the warnings the start of loading found. */
        public LoadDetail withHandoverWarnings(List<HandoverWarning> warnings) {
            return new LoadDetail(loadId, code, warehouseId, status, carrierId, carrierCode, carrierName, vehiclePlate,
                    driverName, driverPhone, loadingStartedAt, loadingStartedBy, dispatchedAt, dispatchedBy,
                    dispatchedByName, carrierRepresentative, signatureRef, handoverNote, cancelledAt, cancelReason,
                    shipmentCount, expectedPackageCount, loadedPackageCount, version, shipments, loadPlanId,
                    loadPlanCode, vehicleSeq, vehicleTypeCode, bookedAt, bookedBy, bookingCancelledAt,
                    bookingCancelReason, pendingUnloads, carrierBookingToCancel, cancelReasonCode, replacementLoadId,
                    replacementLoadCode, waybillNo, carrierConnectionType, carrierBooking, warnings);
        }
    }

    /**
     * 4B-8 (kltn-docs 08 BR-11, 17 §4.4 step 17, 09 BR-18): an order on the vehicle whose goods may not leave yet, found
     * when loading starts - loading goes ahead; the handover will refuse the vehicle until the order may leave.
     *
     * @param reasonCode why (ORDER_BALANCE_NOT_PAID, DELIVERY_BATCH_WINDOW_NOT_OPEN, ...)
     */
    record HandoverWarning(UUID orderId, String orderNumber, String reasonCode, java.math.BigDecimal amountDue,
                           String currency, Integer deliveryBatchSeq, java.time.LocalDate windowStart) { }

    /**
     * The load's latest order at an API_BOOKING carrier (3-4), null when it never had one.
     *
     * @param status      REQUESTED, BOOKED, FAILED, CANCELLED or CANCEL_FAILED (the carrier would not cancel it after
     *                    the load was called off: settle it with the carrier)
     * @param lastError   the carrier's reason for a refusal
     */
    record CarrierBookingView(String status, String carrierOrderId, String trackingLink, Instant requestedAt,
                              Instant bookedAt, String lastError) { }

    /**
     * The answer to the controlled cancel of one vehicle: the load, now CANCELLED, and the PLANNED load that took its
     * place with the same layout (null when the vehicle was called off before and nothing replaced it).
     */
    record ControlledCancelResult(LoadDetail cancelled, LoadDetail replacement) { }

    /** The printable manifest (09 §3 Output "Biên bản bàn giao"): what both sides sign. */
    record Manifest(String loadCode, String status, String warehouseName, String warehouseAddress, String carrierCode,
                    String carrierName, String carrierContactName, String carrierContactPhone, String vehiclePlate,
                    String driverName, String driverPhone, String carrierRepresentative, String signatureRef,
                    String handoverNote, Instant dispatchedAt, String dispatchedByName, List<ManifestShipment> shipments,
                    int shipmentCount, int packageCount, BigDecimal totalWeightKg, Instant printedAt,
                    String loadPlanCode, Integer vehicleSeq, String vehicleTypeCode, String waybillNo) { }

    record ManifestShipment(String shipmentCode, String orderNumber, String recipientName, String recipientPhone,
                            String deliveryAddress, int packageCount, BigDecimal weightKg,
                            List<ManifestParcel> packages) { }

    record ManifestParcel(String labelCode, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                          BigDecimal heightCm, boolean loaded) { }

    /**
     * A load plan's vehicle booked with a carrier outside the system (09 §4.2 step 6); plate, driver and the carrier's
     * waybill number optional ("nhập biển số / tài xế / mã vận đơn nếu đã có").
     */
    record Booking(UUID carrierId, String vehiclePlate, String driverName, String driverPhone, String waybillNo) {

        /** Without the waybill number: given later (3-2). */
        public Booking(UUID carrierId, String vehiclePlate, String driverName, String driverPhone) {
            this(carrierId, vehiclePlate, driverName, driverPhone, null);
        }
    }

    /**
     * The carrier's waybill number for a load, typed (09 §4.4 "(3) Nhập tay"), with the vehicle's plate and driver when
     * known (null keeps what the load has).
     *
     * @param reason required once the vehicle left: the change is then a correction (BR-24)
     */
    record WaybillEntry(String waybillNo, String vehiclePlate, String driverName, String driverPhone, String reason) { }

    /**
     * One change of a load's waybill number (09 §4.4, BR-24).
     *
     * @param newWaybillNo  null when the booking was cancelled and the number went with it
     * @param source        MANUAL, FILE, WEBHOOK or API
     * @param changedByName null for a webhook
     */
    record WaybillChange(String oldWaybillNo, String newWaybillNo, String source, UUID changedBy,
                         String changedByName, Instant changedAt, String reason) { }

    /** @param version the load's version as the dispatcher saw it when the driver signed */
    record Handover(Long version, String carrierRepresentative, String vehiclePlate, String driverPhone,
                    String signatureRef, Boolean signed, String note) { }

    /**
     * Loads of the caller's warehouses, newest first; filters optional ({@code search}: load code, plate or the load
     * plan's code).
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown status
     */
    PageResponse<LoadRow> list(UUID warehouseId, String status, UUID carrierId, String search, int page, int size);

    /** @throws com.stockflow.common.error.BusinessException {@code LOAD_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} */
    LoadDetail get(UUID loadId);

    /**
     * One parcel scanned onto the vehicle by its label code. The same {@code scanId} again answers as before, whatever
     * happened since; another scan id of a parcel already loaded here changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKAGE_NOT_FOUND}, {@code PACKAGE_NOT_READY},
     *         {@code PACKAGE_ALREADY_LOADED}, {@code PACKAGE_NOT_IN_LOAD}, {@code INVALID_LOAD_TRANSITION},
     *         {@code LOAD_SCAN_ID_CONFLICT}
     */
    LoadDetail scan(UUID loadId, UUID scanId, String packageCode, CurrentUser actor);

    /** Takes a scanned parcel off the vehicle again before the handover. Not loaded here: nothing changes. */
    LoadDetail unscan(UUID loadId, UUID packageId, CurrentUser actor);

    /**
     * Both sides sign: DISPATCHED; every shipment on it HANDED_OVER; the orders move (PARTIALLY_FULFILLED / SHIPPED).
     * All or nothing: one shipment the order refuses (unpaid, on hold) refuses the handover, naming it. Already
     * dispatched: answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code HANDOVER_NOT_SIGNED}, {@code VALIDATION_FAILED},
     *         {@code LOAD_EMPTY}, {@code LOAD_NOT_FULLY_LOADED}, {@code OPTIMISTIC_LOCK} (the load changed since
     *         {@code version}), {@code ORDER_BALANCE_NOT_PAID}, {@code ORDER_INVALID_TRANSITION},
     *         {@code ORDER_CANCELLED}, {@code INVALID_LOAD_TRANSITION}
     */
    LoadDetail handOver(UUID loadId, Handover handover, CurrentUser actor);

    /** The manifest to print: shipments, parcels, recipients. */
    Manifest manifest(UUID loadId);

    // ---- the vehicle's booking, loading and controlled cancel (SCRUM-392; 09 §4.2 step 5, BR-14, BR-15) ----------

    /**
     * A confirmed plan's vehicle booked with a carrier: PLANNED → VEHICLE_BOOKED; again before loading, the booking's
     * details are replaced. A waybill number given is recorded (and must be the carrier's only); none given keeps the
     * number while the carrier stays the same and drops it when the carrier changes.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_LOAD_TRANSITION} (loading started),
     *         {@code INVALID_LOAD_PLAN_TRANSITION} (the plan is not CONFIRMED), {@code CARRIER_NOT_FOUND},
     *         {@code CARRIER_INACTIVE}, {@code WAYBILL_DUPLICATE}, {@code CARRIER_BOOKS_THROUGH_API} (an
     *         API_BOOKING carrier is booked through its API, 09 BR-29)
     */
    LoadDetail book(UUID loadId, Booking booking, CurrentUser actor);

    /**
     * The booking fell through before loading: back to PLANNED with the reason, the carrier, vehicle and waybill number
     * cleared (the waybill history keeps the number); {@code INVALID_LOAD_TRANSITION} once loading. A load whose
     * carrier order is held, or being placed, is not un-booked here - its order is cancelled at the carrier first
     * ({@link Bookings#cancelBooking}), so 409 {@code CARRIER_BOOKING_IN_PROGRESS}; a claim the carrier never confirmed
     * is released by this, the dispatcher having checked the carrier's portal.
     */
    LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor);

    /**
     * The carrier cancelled the order {@code bookingId} - at the booking service's request - and this load takes note,
     * in the caller's transaction, under the load's locks and then the booking's: the booking CANCELLED and, while the
     * load is still VEHICLE_BOOKED, the load back to PLANNED. A load that started loading meanwhile keeps its status (the
     * caller tells the dispatcher); its handover is refused, its order being gone. Called only by the booking service.
     */
    LoadDetail cancelCarrierBooking(UUID loadId, UUID bookingId, String reason, CurrentUser actor);

    // ---- what a carrier's webhook reported (3-5; kltn-docs 09 §4.5 step 7) ---------------------------
    // Applied in the caller's transaction under the load's locks; nobody is the actor. Each returns whether the load
    // changed, and throws a BusinessException when the load's state does not take the report - the caller's
    // NEEDS_REVIEW.

    /**
     * The waybill number, with the plate and driver when given (a WEBHOOK_LOAD carrier, 09 §4.4 source 1): history
     * source WEBHOOK. Once the vehicle left, a different number is not applied ({@code INVALID_LOAD_TRANSITION}); the
     * same number changes nothing. A number another live load of the carrier holds is {@code WAYBILL_DUPLICATE}.
     */
    boolean carrierWaybill(UUID loadId, String waybill, String plate, String driver, String phone);

    /** A driver took the carrier's order: name and phone on the load. */
    boolean carrierDriverAssigned(UUID loadId, String driver, String phone);

    /** The driver gave the carrier's order back: off the load. */
    boolean carrierDriverReleased(UUID loadId);

    /** What a carrier's cancel did to a load. */
    enum CarrierCancel {
        /** The booking CANCELLED and the load back to PLANNED, to be booked again. */
        UNBOOKED,
        /** The booking CANCELLED while the vehicle was loading: the load cannot leave on that order. */
        CANCELLED_WHILE_LOADING,
        /** The booking was no longer held: nothing changed. */
        NOT_HELD
    }

    /**
     * The carrier cancelled the order {@code bookingId} before the pickup (09 §4.5: "chuyến chưa ký bàn giao → về
     * Planned, xoá thông tin đặt xe, báo Dispatch Staff"): the booking CANCELLED and, before loading, the load back to
     * PLANNED; while loading, the load stays as it is and its handover is refused, the order being gone. The caller
     * tells the dispatchers. Once the vehicle left, {@code INVALID_LOAD_TRANSITION}.
     */
    CarrierCancel carrierOrderCancelled(UUID loadId, UUID bookingId, Instant at);

    // ---- the waybill number (3-2; 09 §4.4, BR-23, BR-24) --------------------------------------------------------------

    /**
     * Records the carrier's waybill number for a booked load, with its plate and driver when known. Before the vehicle
     * left the dispatcher may change it; after, it is a correction: it needs {@code fulfillment-waybill-corrections}
     * and a reason. Every change is in {@link #waybillHistory}. The same values again change nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code INVALID_LOAD_TRANSITION} (no carrier booked, or cancelled); {@code WAYBILL_FROM_CARRIER_API}
     *         (the carrier's booking API gives it); {@code WAYBILL_DUPLICATE}; {@code VALIDATION_FAILED}; 403 for a
     *         correction without the permission
     */
    LoadDetail recordWaybill(UUID loadId, WaybillEntry entry, CurrentUser actor);

    /** Every change of the load's waybill number, oldest first. */
    List<WaybillChange> waybillHistory(UUID loadId);

    /**
     * The third step of booking through a carrier's API ({@link Bookings#book}), in the caller's transaction: under the
     * load's locks, a load still PLANNED in a confirmed plan is booked with the carrier, the order id as its waybill
     * number (history source API), and the claimed booking marked BOOKED. Called only by the booking service.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_LOAD_TRANSITION} or
     *         {@code INVALID_LOAD_PLAN_TRANSITION} when the load moved since the booking was claimed;
     *         {@code WAYBILL_DUPLICATE}; {@code CONFLICT} when the claim is no longer REQUESTED
     */
    LoadDetail applyCarrierBooking(UUID loadId, UUID carrierId, UUID bookingId, String orderId, String trackingLink,
                                   CurrentUser actor);

    /** The booked vehicle arrived: LOADING (BR-14: the plan is locked from now on). */
    LoadDetail startLoading(UUID loadId, CurrentUser actor);

    /**
     * A parcel of a cancelled order was taken off the vehicle (scanned off). With nothing left to carry, the vehicle is
     * called off and the plan DISPATCHED if every other vehicle left.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKAGE_NOT_FOUND}, {@code PACKAGE_NOT_IN_LOAD}
     */
    LoadDetail unload(UUID loadId, String packageCode, CurrentUser actor);

    // ---- controlled unload after loading started (SCRUM-474 UNLOAD-DESIGN; 09 BR-14) ---------------------------------

    /**
     * Calls off ONE vehicle that is being loaded (a broken or wrong truck), under control: its load is CANCELLED at once
     * with the reason, its scanned parcels stay locked until each is scanned off ({@link #unload}) or confirmed off
     * ({@link #overrideUnload}), and a new PLANNED load with the same manifest takes its place so another truck can be
     * booked. The plan and its other vehicles carry on. Cancelled already: answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_NOT_FOUND}; {@code INVALID_LOAD_TRANSITION}
     *         (not LOADING); {@code INVALID_LOAD_PLAN_TRANSITION}; {@code VALIDATION_FAILED}
     */
    ControlledCancelResult cancelControlled(UUID loadId, com.stockflow.fulfillment.internal.domain.UnloadReasonCode code,
                                            String note, CurrentUser actor);

    /**
     * SCRUM-474 UNLOAD-DROP: drops a vehicle that has not loaded - its load still PLANNED, no truck booked - from its
     * confirmed plan, when every other vehicle of the plan already left. Its load is CANCELLED with the reason code and
     * the note (required); the vehicle leaves the plan and the parcels placed on it wait for a new plan (nothing was
     * scanned, so nothing is locked); the plan is DISPATCHED. Dropped already: answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_NOT_FOUND}; {@code INVALID_LOAD_TRANSITION} (not
     *         PLANNED); {@code INVALID_LOAD_PLAN_TRANSITION}; {@code LOAD_PLAN_OTHER_VEHICLES_NOT_LEFT};
     *         {@code LOAD_PLAN_VEHICLE_SHARES_ORDER}; {@code VALIDATION_FAILED}
     */
    LoadDetail removeFromPlan(UUID loadId, com.stockflow.fulfillment.internal.domain.UnloadReasonCode code, String note,
                              CurrentUser actor);

    /**
     * A parcel still to unload is confirmed off the vehicle without a scan (its label is damaged): who, when and the
     * note stay on record. Needs the controlled-cancellation permission, a warehouse manager's.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKAGE_NOT_FOUND}, {@code PACKAGE_NOT_IN_LOAD}
     */
    LoadDetail overrideUnload(UUID loadId, UUID packageId, String note, CurrentUser actor);
}

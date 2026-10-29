package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Loading and handover, parcel branch (G4-2a, SCRUM-288/292/293; kltn-docs 09 §4.1): a load is one handover to one
 * carrier's vehicle at one warehouse. The dispatcher opens it when the driver arrives, with the shipments waiting for
 * that carrier; scans each parcel onto the vehicle against that list; takes off what is not going; and both sides sign
 * the manifest. The signature hands the shipments over - each with all its parcels (BR-03) - and tells the order in the
 * same transaction, with the COD the order fixes for each one (BR-04), so the order turns PARTIALLY_FULFILLED or
 * SHIPPED and a prepaid order not fully paid is refused (G3-Q4).
 *
 * <p>Inside {@code internal}: only this module's web layer calls it. Every write locks the load's row first, then the
 * shipments' rows in id order, then (through {@code OrderService}) the orders': two dispatchers on one load queue up,
 * and the second sees what the first did. Every call is limited to the caller's warehouses ({@code OUT_OF_DATA_SCOPE}).
 * </p>
 */
public interface Dispatching {

    /** @param loadPlanId the plan of a full-truckload load (SCRUM-392), with its code and the vehicle's seq; null else */
    record LoadRow(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                   String carrierName, String vehiclePlate, String driverName, int shipmentCount, int packageCount,
                   BigDecimal codTotal, Instant loadingStartedAt, Instant dispatchedAt, long version, UUID loadPlanId,
                   String loadPlanCode, Integer vehicleSeq) { }

    /**
     * A parcel still to take off a vehicle (SCRUM-392: its order was cancelled; SCRUM-474: its vehicle was called off
     * under control). {@code reasonCode} only for the second kind. On a transfer plan's vehicle the parcel's key is its
     * transfer order: {@code shipmentId} is null, {@code transferOrderId} and {@code shipmentCode} (its number) are set.
     */
    record PendingUnload(UUID packageId, String labelCode, UUID shipmentId, String shipmentCode, String reason,
                         Instant requestedAt, String reasonCode, UUID transferOrderId) { }

    /** @param kit the kit it is a part of (SCRUM-287 LP2-6): every part is scanned; null for an ordinary parcel */
    record LoadParcel(UUID packageId, String labelCode, BigDecimal weightKg, boolean loaded, Instant loadedAt,
                      UUID loadedBy, com.stockflow.fulfillment.internal.domain.KitPart kit, Instant labelOutdatedAt) { }

    /**
     * A shipment on the manifest.
     *
     * @param taskId         the fulfillment task it was packed in: where its label is reprinted ({@code LABEL_OUTDATED})
     * @param labelCodAmount what its label says the carrier collects
     * @param codAmount      what the order fixed at the handover; null before
     * @param currency       of both amounts: the order's
     * @param transferOrderId SCRUM-287 PR T: on a TRANSFER plan's vehicle the line is its transfer order -
     *                       {@code shipmentId}, {@code orderId} and {@code taskId} are null, {@code code} is the
     *                       transfer's number, {@code status} the transfer's
     */
    record LoadShipment(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, String status,
                        int packageCount, int loadedCount, BigDecimal labelCodAmount, BigDecimal codAmount,
                        String currency, List<LoadParcel> packages, UUID transferOrderId) { }

    record LoadDetail(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                      String carrierCode, String carrierName, String vehiclePlate, String driverName,
                      String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                      UUID dispatchedBy, String dispatchedByName, String carrierRepresentative, String signatureRef,
                      String handoverNote, Instant cancelledAt, String cancelReason, int shipmentCount,
                      int expectedPackageCount, int loadedPackageCount, BigDecimal codTotal, String currency,
                      long version, List<LoadShipment> shipments, UUID loadPlanId, String loadPlanCode,
                      Integer vehicleSeq, String vehicleTypeCode, Instant bookedAt, UUID bookedBy,
                      Instant bookingCancelledAt, String bookingCancelReason, List<PendingUnload> pendingUnloads,
                      boolean carrierBookingToCancel, String cancelReasonCode) { }

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
                    int shipmentCount, int packageCount, BigDecimal totalWeightKg, BigDecimal codTotal,
                    String currency, Instant printedAt, String mode, String loadPlanCode, Integer vehicleSeq,
                    String vehicleTypeCode) { }

    /**
     * @param transferOrderId SCRUM-287 PR T: a transfer order on a TRANSFER plan's vehicle - {@code shipmentCode} is its
     *                        number, the recipient and address its destination warehouse's
     */
    record ManifestShipment(String shipmentCode, String orderNumber, String recipientName, String recipientPhone,
                            String deliveryAddress, int packageCount, BigDecimal weightKg, BigDecimal codAmount,
                            boolean codChangedSinceLabel, List<ManifestParcel> packages, UUID transferOrderId) { }

    record ManifestParcel(String labelCode, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                          BigDecimal heightCm, boolean loaded, com.stockflow.fulfillment.internal.domain.KitPart kit,
                          Instant labelOutdatedAt) { }

    /**
     * @param shipmentIds the shipments to put on the manifest now; null or empty: every shipment of that warehouse
     *                    waiting for dispatch for this carrier (or for none chosen yet) and on no other load
     */
    record OpenLoad(UUID requestId, UUID warehouseId, UUID carrierId, List<UUID> shipmentIds, String vehiclePlate,
                    String driverName, String driverPhone) { }

    /** A load plan's vehicle booked with a carrier outside the system (09 §4.2 step 5); plate and driver optional. */
    record Booking(UUID carrierId, String vehiclePlate, String driverName, String driverPhone) { }

    /** @param version the load's version as the dispatcher saw it when the driver signed */
    record Handover(Long version, String carrierRepresentative, String vehiclePlate, String driverPhone,
                    String signatureRef, Boolean signed, String note) { }

    /**
     * Loads of the caller's warehouses, newest first; filters optional ({@code mode}: PARCEL or FULL_TRUCKLOAD,
     * {@code search}: load code, plate or - for a plan's vehicle - the load plan's code).
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown status or mode
     */
    PageResponse<LoadRow> list(UUID warehouseId, String status, String mode, UUID carrierId, String search, int page,
                               int size);

    /** @throws com.stockflow.common.error.BusinessException {@code LOAD_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} */
    LoadDetail get(UUID loadId);

    /**
     * The driver arrived: a load LOADING (§4.1 step 3). The same {@code requestId} again answers with that load.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}, {@code CARRIER_INACTIVE},
     *         {@code WAREHOUSE_NOT_FOUND}, {@code SHIPMENT_NOT_FOUND} and every refusal of {@link #addShipments}
     */
    LoadDetail open(OpenLoad request, CurrentUser actor);

    /**
     * Puts shipments on the manifest: waiting for dispatch, of this warehouse, of the load's carrier or of none chosen
     * yet (they take the load's), on no other open load. Already on it: nothing changes.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_STATE_CONFLICT},
     *         {@code SHIPMENT_NOT_FOR_LOAD}, {@code SHIPMENT_OTHER_CARRIER}, {@code SHIPMENT_ON_ANOTHER_LOAD},
     *         {@code PACKAGE_REQUIRES_LOAD_PLAN}, {@code INVALID_LOAD_TRANSITION}
     */
    LoadDetail addShipments(UUID loadId, List<UUID> shipmentIds, CurrentUser actor);

    /** Takes a shipment and its scanned parcels off the manifest before the handover. Not on it: nothing changes. */
    LoadDetail removeShipment(UUID loadId, UUID shipmentId, CurrentUser actor);

    /**
     * One parcel scanned onto the vehicle by its label code. The same {@code scanId} again answers as before, whatever
     * happened since; another scan id of a parcel already loaded here changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKAGE_NOT_FOUND}, {@code PACKAGE_NOT_READY},
     *         {@code PACKAGE_ALREADY_LOADED}, {@code PACKAGE_NOT_IN_LOAD}, {@code PACKAGE_OTHER_CARRIER},
     *         {@code PACKAGE_REQUIRES_LOAD_PLAN}, {@code INVALID_LOAD_TRANSITION}, {@code LOAD_SCAN_ID_CONFLICT}
     */
    LoadDetail scan(UUID loadId, UUID scanId, String packageCode, CurrentUser actor);

    /** Takes a scanned parcel off the vehicle again before the handover. Not loaded here: nothing changes. */
    LoadDetail unscan(UUID loadId, UUID packageId, CurrentUser actor);

    /**
     * Both sides sign: DISPATCHED; every shipment on it HANDED_OVER with the COD the order fixes; the orders move
     * (PARTIALLY_FULFILLED / SHIPPED). All or nothing: one shipment the order refuses (unpaid, on hold) refuses the
     * handover, naming it - take it off the manifest to let the rest go. Already dispatched: answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code HANDOVER_NOT_SIGNED}, {@code VALIDATION_FAILED},
     *         {@code LOAD_EMPTY}, {@code LOAD_NOT_FULLY_LOADED}, {@code OPTIMISTIC_LOCK} (the load changed since
     *         {@code version}), {@code ORDER_BALANCE_NOT_PAID}, {@code ORDER_INVALID_TRANSITION},
     *         {@code ORDER_CANCELLED}, {@code INVALID_LOAD_TRANSITION}
     */
    LoadDetail handOver(UUID loadId, Handover handover, CurrentUser actor);

    /** Called off before it left; its shipments and parcels go back to the dispatch area. */
    LoadDetail cancel(UUID loadId, String reason, CurrentUser actor);

    /** The manifest to print: shipments, parcels, recipients, COD per shipment and in total. */
    Manifest manifest(UUID loadId);

    // ---- full truckload (SCRUM-392; 09 §4.2 step 5, BR-14, BR-15; LP-3, LP-11, LP2-1) ----------------------------

    /**
     * A confirmed plan's vehicle booked with a carrier: PLANNED → VEHICLE_BOOKED; again before loading, the booking's
     * details are replaced.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_LOAD_TRANSITION} (a parcel load, or loading
     *         started), {@code INVALID_LOAD_PLAN_TRANSITION} (the plan is not CONFIRMED), {@code CARRIER_NOT_FOUND},
     *         {@code CARRIER_INACTIVE}, {@code SHIPMENT_OTHER_CARRIER} (a shipment on it was chosen for another
     *         carrier)
     */
    LoadDetail book(UUID loadId, Booking booking, CurrentUser actor);

    /** The booking fell through before loading: back to PLANNED with the reason; {@code INVALID_LOAD_TRANSITION} once loading. */
    LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor);

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
     *         (a parcel load, or not LOADING); {@code INVALID_LOAD_PLAN_TRANSITION}; {@code VALIDATION_FAILED}
     */
    ControlledCancelResult cancelControlled(UUID loadId, com.stockflow.fulfillment.internal.domain.UnloadReasonCode code,
                                            String note, CurrentUser actor);

    /**
     * A parcel still to unload is confirmed off the vehicle without a scan (its label is damaged): who, when and the
     * note stay on record. Needs the controlled-cancellation permission, a warehouse manager's.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKAGE_NOT_FOUND}, {@code PACKAGE_NOT_IN_LOAD}
     */
    LoadDetail overrideUnload(UUID loadId, UUID packageId, String note, CurrentUser actor);
}

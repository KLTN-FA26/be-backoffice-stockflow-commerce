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

    record LoadRow(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                   String carrierName, String vehiclePlate, String driverName, int shipmentCount, int packageCount,
                   BigDecimal codTotal, Instant loadingStartedAt, Instant dispatchedAt, long version) { }

    record LoadParcel(UUID packageId, String labelCode, BigDecimal weightKg, boolean loaded, Instant loadedAt,
                      UUID loadedBy) { }

    /**
     * A shipment on the manifest.
     *
     * @param labelCodAmount what its label says the carrier collects
     * @param codAmount      what the order fixed at the handover; null before
     */
    record LoadShipment(UUID shipmentId, String code, UUID orderId, String orderNumber, String status,
                        int packageCount, int loadedCount, BigDecimal labelCodAmount, BigDecimal codAmount,
                        List<LoadParcel> packages) { }

    record LoadDetail(UUID loadId, String code, UUID warehouseId, String mode, String status, UUID carrierId,
                      String carrierCode, String carrierName, String vehiclePlate, String driverName,
                      String driverPhone, Instant loadingStartedAt, UUID loadingStartedBy, Instant dispatchedAt,
                      UUID dispatchedBy, String dispatchedByName, String carrierRepresentative, String signatureRef,
                      String handoverNote, Instant cancelledAt, String cancelReason, int shipmentCount,
                      int expectedPackageCount, int loadedPackageCount, BigDecimal codTotal, String currency,
                      long version, List<LoadShipment> shipments) { }

    /** The printable manifest (09 §3 Output "Biên bản bàn giao"): what both sides sign. */
    record Manifest(String loadCode, String status, String warehouseName, String warehouseAddress, String carrierCode,
                    String carrierName, String carrierContactName, String carrierContactPhone, String vehiclePlate,
                    String driverName, String driverPhone, String carrierRepresentative, String signatureRef,
                    String handoverNote, Instant dispatchedAt, String dispatchedByName, List<ManifestShipment> shipments,
                    int shipmentCount, int packageCount, BigDecimal totalWeightKg, BigDecimal codTotal,
                    String currency, Instant printedAt) { }

    record ManifestShipment(String shipmentCode, String orderNumber, String recipientName, String recipientPhone,
                            String deliveryAddress, int packageCount, BigDecimal weightKg, BigDecimal codAmount,
                            boolean codChangedSinceLabel, List<ManifestParcel> packages) { }

    record ManifestParcel(String labelCode, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm,
                          BigDecimal heightCm, boolean loaded) { }

    /**
     * @param shipmentIds the shipments to put on the manifest now; null or empty: every shipment of that warehouse
     *                    waiting for dispatch for this carrier (or for none chosen yet) and on no other load
     */
    record OpenLoad(UUID requestId, UUID warehouseId, UUID carrierId, List<UUID> shipmentIds, String vehiclePlate,
                    String driverName, String driverPhone) { }

    /** @param version the load's version as the dispatcher saw it when the driver signed */
    record Handover(Long version, String carrierRepresentative, String vehiclePlate, String driverPhone,
                    String signatureRef, Boolean signed, String note) { }

    /** Loads of the caller's warehouses, newest first; filters optional ({@code search}: load code or plate). */
    PageResponse<LoadRow> list(UUID warehouseId, String status, UUID carrierId, String search, int page, int size);

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
}

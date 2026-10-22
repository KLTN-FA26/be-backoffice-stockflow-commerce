package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shipments as the back office sees them (G4-2a; kltn-docs 09 §5): the ones waiting at dispatch, the carrier chosen
 * for them, and - once handed over - the outcome staff record when the carrier reports back (§4.3, BR-02): delivered,
 * not delivered (with a reason), back at the warehouse. Each outcome tells the order in the same transaction, which
 * bills it (a credit order's receivable, a deposit order's carrier-collected COD) and moves the order (BR-05).
 *
 * <p>Inside {@code internal}: only this module's web layer calls it. Every call is limited to the caller's warehouses.</p>
 */
public interface Shipments {

    /**
     * @param taskId     the fulfillment task it was packed in (where its label is reprinted); null for none
     * @param loadCode   the open or dispatched load it is on; null when on none
     * @param codAmount  what the carrier collects: the label's figure until the handover fixes it (09 BR-04)
     * @param currency   of {@code codAmount}: the order's
     */
    record ShipmentRow(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, UUID warehouseId,
                       String status, String deliveryMode, int packageCount, BigDecimal codAmount, String currency,
                       UUID carrierId, String carrierName,
                       UUID loadId, String loadCode, boolean partial, Instant readyAt, Instant handedOverAt,
                       Instant outcomeAt, String failureReason, boolean wholeOrderHandover, long version) { }

    record Parcel(UUID packageId, String labelCode, String status, BigDecimal weightKg, BigDecimal lengthCm,
                  BigDecimal widthCm, BigDecimal heightCm, com.stockflow.fulfillment.internal.domain.KitPart kit) { }

    /** One step of the timeline, with who recorded it (09 BR-02). */
    record Step(String status, Instant occurredAt, UUID recordedBy, String recordedByName, String note) { }

    record ShipmentDetail(ShipmentRow shipment, String partialNote, String failureNote, Instant cancelledAt,
                          List<Parcel> packages, List<Step> timeline) { }

    /**
     * @param outcome       DELIVERED, DELIVERY_FAILED or RETURNED
     * @param failureReason with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER
     * @param note          required with OTHER; staff only, never shown to the customer
     */
    record Outcome(String outcome, String failureReason, String note) { }

    /** Newest first; every filter optional ({@code search}: shipment code). */
    PageResponse<ShipmentRow> list(UUID warehouseId, String status, UUID orderId, UUID carrierId, String search,
                                   int page, int size);

    /** @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} */
    ShipmentDetail get(UUID shipmentId);

    /**
     * The carrier a shipment waiting for dispatch will go with (09 §4.1 step 2); null clears it. Not while it is on a
     * load: take it off first.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARRIER_NOT_FOUND}, {@code CARRIER_INACTIVE},
     *         {@code SHIPMENT_STATE_CONFLICT}, {@code SHIPMENT_ON_ANOTHER_LOAD}
     */
    ShipmentDetail assignCarrier(UUID shipmentId, UUID carrierId, CurrentUser actor);

    /**
     * What the carrier reported (09 §4.3): HANDED_OVER → DELIVERED / DELIVERY_FAILED; DELIVERY_FAILED → DELIVERED (a
     * retry) / RETURNED. The same outcome again changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_STATE_CONFLICT} along any other edge;
     *         {@code VALIDATION_FAILED} (no reason, OTHER without a note); {@code SHIPMENT_NOT_FOUND}
     */
    ShipmentDetail recordOutcome(UUID shipmentId, Outcome outcome, CurrentUser actor);
}

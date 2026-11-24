package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shipments as the back office sees them (G4-2a; kltn-docs 09 §5): the ones waiting at dispatch for a load plan, and -
 * once handed over with their vehicle's carrier - the outcome staff record when the carrier reports back (§4.3, BR-02):
 * delivered, not delivered (with a reason), back at the warehouse. Each outcome tells the order in the same
 * transaction, which bills it (a credit order's receivable) and moves the order (BR-05). Nothing is collected on
 * delivery: there is no COD (09 BR-08).
 *
 * <p>Inside {@code internal}: only this module's web layer calls it. Every call is limited to the caller's warehouses.</p>
 */
public interface Shipments {

    /**
     * @param taskId    the fulfillment task it was packed in (where its label is reprinted); null for none
     * @param carrierId the carrier it was handed to; null before the handover
     * @param loadCode  the open or dispatched load it is on; null when on none
     */
    record ShipmentRow(UUID shipmentId, String code, UUID orderId, String orderNumber, UUID taskId, UUID warehouseId,
                       String status, int packageCount, UUID carrierId, String carrierName,
                       UUID loadId, String loadCode, boolean partial, Instant readyAt, Instant handedOverAt,
                       Instant outcomeAt, String failureReason, boolean wholeOrderHandover, long version) { }

    record Parcel(UUID packageId, String labelCode, String status, BigDecimal weightKg, BigDecimal lengthCm,
                  BigDecimal widthCm, BigDecimal heightCm) { }

    /**
     * One step of the timeline, with where it came from and who or what recorded it (09 BR-02): {@code source} MANUAL
     * (a person, {@code recordedBy}) or CARRIER_WEBHOOK (nobody; {@code carrierEventId} names the carrier's event).
     */
    record Step(String status, Instant occurredAt, UUID recordedBy, String recordedByName, String note, String source,
                UUID carrierEventId) { }

    /**
     * A failure reason the warehouse manager corrected: before and after, why, who and when.
     */
    record ReasonCorrection(String fromReason, String fromNote, String toReason, String toNote, String reason,
                            UUID correctedBy, String correctedByName, Instant correctedAt) { }

    /**
     * @param undoneDeliveredAt audit r3 EINV-1: on a shipment whose failure is the warehouse manager's correction of a
     *                          delivery, the time of the delivery that correction undid - what a correction back to
     *                          delivered is dated by when it gives no {@code deliveredAt}, and what the screen fills
     *                          the field with. Another day than that one makes it a new delivery: its issued invoice
     *                          stays reduced to nothing and a new original is drawn up for the new day. Null otherwise.
     */
    record ShipmentDetail(ShipmentRow shipment, String partialNote, String failureNote, Instant cancelledAt,
                          List<Parcel> packages, List<Step> timeline, List<ReasonCorrection> reasonCorrections,
                          Instant undoneDeliveredAt) { }

    /**
     * @param outcome       DELIVERED, DELIVERY_FAILED or RETURNED
     * @param failureReason with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER
     * @param note          required with OTHER; staff only, never shown to the customer
     * @param deliveredAt   only with DELIVERED: when the carrier delivered it; null = now
     */
    record Outcome(String outcome, String failureReason, String note, Instant deliveredAt) {

        public Outcome(String outcome, String failureReason, String note) {
            this(outcome, failureReason, note, null);
        }
    }

    /** Newest first; every filter optional ({@code search}: shipment code). */
    PageResponse<ShipmentRow> list(UUID warehouseId, String status, UUID orderId, UUID carrierId, String search,
                                   int page, int size);

    /** @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE} */
    ShipmentDetail get(UUID shipmentId);

    /**
     * What the carrier reported (09 §4.3): HANDED_OVER → DELIVERED / DELIVERY_FAILED; DELIVERY_FAILED → DELIVERED (a
     * retry) / RETURNED. The same outcome again changes nothing. A delivery is dated {@code deliveredAt} when given
     * (the carrier's time, which dates the order's delivery and the draft invoice), else now.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_STATE_CONFLICT} along any other edge;
     *         {@code VALIDATION_FAILED} (no reason, OTHER without a note, a delivery time with another outcome);
     *         {@code SHIPMENT_DELIVERY_TIME_IN_FUTURE}; {@code SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER};
     *         {@code SHIPMENT_NOT_FOUND}
     */
    ShipmentDetail recordOutcome(UUID shipmentId, Outcome outcome, CurrentUser actor);

    /**
     * What the warehouse manager corrects (09 §4.3 step 5, E17).
     *
     * @param outcome       DELIVERED or DELIVERY_FAILED: what the shipment really came to
     * @param failureReason required with DELIVERY_FAILED
     * @param note          required with OTHER
     * @param reason        why the carrier's report was wrong; required
     * @param deliveredAt   only when correcting to DELIVERED: when it was delivered; null = now
     */
    /**
     * @param deliveredAt only with DELIVERED: when it was delivered. Null = the delivery the failure being corrected
     *                    undid, when that failure was itself a correction of a delivery (audit r3 EINV-1: the same
     *                    delivery, so its invoice stands for it again); otherwise now
     */
    record Correction(String outcome, String failureReason, String note, String reason, Instant deliveredAt) {

        public Correction(String outcome, String failureReason, String note, String reason) {
            this(outcome, failureReason, note, reason, null);
        }
    }

    /**
     * Corrects an outcome the carrier reported wrongly: DELIVERED ↔ DELIVERY_FAILED while the order is not completed,
     * with a reason; the order follows, and the shipment takes no carrier report for its outcome any more (E17).
     *
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code SHIPMENT_STATE_CONFLICT}; {@code ORDER_DELIVERY_NOT_CORRECTABLE}; {@code VALIDATION_FAILED}
     */
    ShipmentDetail correctOutcome(UUID shipmentId, Correction correction, CurrentUser actor);

    /**
     * @param failureReason the real reason: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER
     * @param note          required with OTHER
     * @param reason        why it is corrected (how the real reason was learnt); required
     */
    record ReasonCorrectionRequest(String failureReason, String note, String reason) { }

    /**
     * Corrects the failure reason of a failed or returned shipment once the warehouse manager learnt the real one
     * (kltn-docs 09 edge "hoàn hàng khi còn Handed Over"), with why; the order's shipment follows. The status does not
     * change, and the carrier's later reports still apply. The same reason and note again change nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code SHIPMENT_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code SHIPMENT_STATE_CONFLICT}; {@code SHIPMENT_FAILURE_REASON_LOCKED} once its returned goods were
     *         received; {@code VALIDATION_FAILED}
     */
    ShipmentDetail correctFailureReason(UUID shipmentId, ReasonCorrectionRequest correction, CurrentUser actor);
}

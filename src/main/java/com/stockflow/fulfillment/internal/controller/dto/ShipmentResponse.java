package com.stockflow.fulfillment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One shipment with its parcels and timeline (who recorded each step, 09 BR-02). Staff only.
 *
 * <p>{@code undoneDeliveredAt} (audit r3 EINV-1): the screen that corrects the shipment back to delivered fills its time
 * with it, and warns when the day entered differs - the old invoice then stays reduced and a new one is drawn up. It
 * reached the service's detail but not this answer, so the back office never received it.</p>
 */
public record ShipmentResponse(ShipmentRowResponse shipment, String partialNote, String failureNote,
                               Instant cancelledAt, List<Parcel> packages, List<Step> timeline,
                               List<ReasonCorrection> reasonCorrections,
                               @Schema(description = "Audit r3 EINV-1: while the shipment is DELIVERY_FAILED by the "
                                       + "warehouse manager's correction of a delivery, the time of the delivery that "
                                       + "correction undid - what a correction back to DELIVERED is dated by when it "
                                       + "sends no deliveredAt. Null otherwise.", nullable = true)
                               Instant undoneDeliveredAt) {

    public record Parcel(UUID packageId, String labelCode, String status, BigDecimal weightKg, BigDecimal lengthCm,
                         BigDecimal widthCm, BigDecimal heightCm) {
    }

    /** A failure reason the warehouse manager corrected (09 edge "hoàn hàng khi còn Handed Over"). */
    public record ReasonCorrection(String fromReason, String fromNote, String toReason, String toNote, String reason,
                                   UUID correctedBy, String correctedByName, Instant correctedAt) {
    }

    /**
     * @param source         MANUAL (a person recorded it) or CARRIER_WEBHOOK (the carrier's webhook reported it)
     * @param carrierEventId the carrier event a CARRIER_WEBHOOK step came from; null for a MANUAL one
     */
    public record Step(String status, Instant occurredAt, UUID recordedBy, String recordedByName, String note,
                       String source, UUID carrierEventId) {
    }
}

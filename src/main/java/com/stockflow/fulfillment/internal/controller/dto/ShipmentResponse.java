package com.stockflow.fulfillment.internal.controller.dto;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One shipment with its parcels and timeline (who recorded each step, 09 BR-02). Staff only. */
public record ShipmentResponse(ShipmentRowResponse shipment, String partialNote, String failureNote,
                               Instant cancelledAt, List<Parcel> packages, List<Step> timeline,
                               List<ReasonCorrection> reasonCorrections) {

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

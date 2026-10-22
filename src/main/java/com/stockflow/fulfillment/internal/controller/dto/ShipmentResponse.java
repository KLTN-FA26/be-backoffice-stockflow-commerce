package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One shipment with its parcels and timeline (who recorded each step, 09 BR-02). Staff only. */
public record ShipmentResponse(ShipmentRowResponse shipment, String partialNote, String failureNote,
                               Instant cancelledAt, List<Parcel> packages, List<Step> timeline) {

    /** @param kit the kit it is a part of (SCRUM-287 LP2-6); absent for an ordinary parcel */
    public record Parcel(UUID packageId, String labelCode, String status, BigDecimal weightKg, BigDecimal lengthCm,
                         BigDecimal widthCm, BigDecimal heightCm,
                         @JsonInclude(JsonInclude.Include.NON_NULL) KitPartResponse kit) {
    }

    public record Step(String status, Instant occurredAt, UUID recordedBy, String recordedByName, String note) {
    }
}

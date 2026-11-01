package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(name = "PurchaseOrderEvent", description = "One entry of a purchase order's timeline")
public record PurchaseOrderEventResponse(
        UUID id,
        String action,
        String fromStatus,
        String toStatus,
        UUID actorId,
        String actorName,
        String reason,
        @Schema(description = "The revision the entry names, when it names one") Long revisionNo,
        @Schema(description = "Extra facts as stored with the entry, e.g. a draft edit's changes") Map<String, Object> payload,
        Instant occurredAt) {}

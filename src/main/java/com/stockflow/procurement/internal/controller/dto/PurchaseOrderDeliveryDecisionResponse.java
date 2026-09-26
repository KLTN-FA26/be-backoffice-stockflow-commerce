package com.stockflow.procurement.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderDeliveryDecisionResponse(UUID id, int generation, LocalDate previousExpectedAt,
        LocalDate expectedAt, String reason, boolean reconciled, boolean acknowledgePastDue,
        String channel, String recipient, String actor, Instant requestedAt) {}

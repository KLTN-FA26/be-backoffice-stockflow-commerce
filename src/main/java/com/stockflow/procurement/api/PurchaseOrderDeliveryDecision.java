package com.stockflow.procurement.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderDeliveryDecision(UUID id, int generation, LocalDate previousExpectedAt,
        LocalDate expectedAt, String reason, boolean reconciled, boolean acknowledgePastDue,
        String channel, String recipient, String actor, Instant requestedAt) {}

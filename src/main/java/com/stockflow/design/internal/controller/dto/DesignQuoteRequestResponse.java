package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer's quote request on their design (kltn-docs 12 §4.8; 16 §4.2): what was asked, and the design as it is now
 * - the card shows the design's current status (16 BR-11). {@code customerSku} is set once the design is approved, the
 * only time it is quoted (12 BR-15); {@code returnReason} while it is a draft again after being returned (B40).
 */
public record DesignQuoteRequestResponse(UUID id, UUID designId, String designName, String designStatus,
                                         UUID customerId, String customerName, String sku, String customerSku,
                                         String returnReason, int cartons, LocalDate desiredDeliveryDate,
                                         String status, UUID requestedBy, Instant requestedAt, Instant closedAt) {
}

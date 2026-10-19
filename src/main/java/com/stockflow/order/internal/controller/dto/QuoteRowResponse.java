package com.stockflow.order.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record QuoteRowResponse(UUID id, String quoteNumber, UUID customerId, String status, int revision,
                               String paymentTerm, Instant validUntil, Instant sentAt, UUID convertedOrderId,
                               Instant createdAt, long version) {
}

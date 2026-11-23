package com.stockflow.order.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A quote in a list.
 *
 * @param total         R5-2: VND with VAT, as the quote's detail adds it up: the accepted total once accepted, else
 *                      the proposed one; null while a part of the offer is not priced
 * @param lineCount     R5-2: lines on the offer shown
 * @param firstItemName R5-2: the design name of the first line, else its SKU; null for an offer with no line
 */
public record QuoteRowResponse(UUID id, String quoteNumber, UUID customerId, String status, int revision,
                               Instant validUntil, Instant sentAt, UUID convertedOrderId,
                               Instant createdAt, long version, BigDecimal total, Integer lineCount,
                               String firstItemName) {
}

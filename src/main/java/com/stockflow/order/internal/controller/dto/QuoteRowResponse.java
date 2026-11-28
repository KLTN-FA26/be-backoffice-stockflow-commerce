package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param customerName  whose quote it is, read for the whole page at once so a list needs no customer lookup per row;
 *                      null for an unknown customer
 * @param customerCode  that customer's code
 * @param convertedOrderNumber the number of the order {@code convertedOrderId} names; null when there is none, and on
 *                      the customer's copy while that order is still a DRAFT (its contract not yet sent, 17 §5.2)
 * @param priceReviewPending 14 BR-10: the draft waits for the Sales Manager to approve its prices; false on the
 *                      customer's copy
 * @param convertedOrderStatus the status of that order now (an {@code OrderStatus} name), so the list tells an order
 *                      under way from one cancelled without opening it; null exactly when {@code convertedOrderNumber} is
 */
public record QuoteRowResponse(UUID id, String quoteNumber, UUID customerId, String status, int revision,
                               Instant validUntil, Instant sentAt, UUID convertedOrderId,
                               Instant createdAt, long version, BigDecimal total, Integer lineCount,
                               String firstItemName, String customerName, String customerCode,
                               String convertedOrderNumber, boolean priceReviewPending,
                               @Schema(example = "CONFIRMED", description = "The status of the order the quote became "
                                       + "(an order status name: DRAFT, PENDING_PAYMENT, CONFIRMED, ...); null when "
                                       + "convertedOrderNumber is null. Parse tolerantly: more may come")
                               String convertedOrderStatus) {
}

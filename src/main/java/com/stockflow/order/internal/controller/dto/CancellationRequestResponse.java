package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One customer request to cancel an order (SCRUM-460). R5-6: on the queue ({@code GET /orders/cancellation-requests})
 * each row carries what Sales decide it on; null on the answer to a rejection.
 */
@Schema(name = "OrderCancellationRequest")
public record CancellationRequestResponse(UUID requestId, UUID orderId, UUID requestedBy,
                                          @Schema(example = "CUSTOMER_REQUEST") String reasonCode, String note,
                                          Instant requestedAt, @Schema(example = "PENDING") String status,
                                          UUID decidedBy, Instant decidedAt, String decisionNote,
                                          BigDecimal retainedPercent, long version,
                                          @Schema(example = "SO-20261108-000007") String orderNumber,
                                          @Schema(description = "The customer's registered name") String customerName,
                                          @Schema(description = "The order's total with VAT") BigDecimal orderTotal,
                                          @Schema(description = "Paid on the order's installments; 0 before its "
                                                  + "contract is signed") BigDecimal paidTotal,
                                          @Schema(example = "IN_PRODUCTION") String orderStatus,
                                          @Schema(description = "Printing was recorded started: cancelling now costs "
                                                  + "a fee (17 §4.7)") Boolean printingStarted) {

    public CancellationRequestResponse(UUID requestId, UUID orderId, UUID requestedBy, String reasonCode, String note,
                                       Instant requestedAt, String status, UUID decidedBy, Instant decidedAt,
                                       String decisionNote, BigDecimal retainedPercent, long version) {
        this(requestId, orderId, requestedBy, reasonCode, note, requestedAt, status, decidedBy, decidedAt, decisionNote,
                retainedPercent, version, null, null, null, null, null, null);
    }
}

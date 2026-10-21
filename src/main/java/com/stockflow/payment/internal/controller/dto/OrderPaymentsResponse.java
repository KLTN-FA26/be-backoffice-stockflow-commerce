package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order's money as the ledger has it (G2-3): what was collected (net allocations and earlier payments, less refunds
 * asked for), what its receivables still owe, what a transfer may still pay to it, and every movement - allocations
 * and reversals with the transfer each came from, receivables, refunds.
 */
@Schema(name = "OrderPayments")
public record OrderPaymentsResponse(UUID orderId, String orderNumber, UUID customerId, String status,
                                    String paymentTerm, String balancePolicy, String currency, BigDecimal billable,
                                    BigDecimal depositRequired, BigDecimal collected, BigDecimal outstandingReceivables,
                                    BigDecimal codInTransit, BigDecimal mayStillTake, List<Movement> movements,
                                    List<ReceivableResponse> receivables, List<Refund> refunds) {

    /** One allocation or reversal, with the statement line it came from. */
    public record Movement(UUID allocationId, UUID transferId, String reference, String channel, LocalDate receivedOn,
                           @Schema(allowableValues = {"ORDER", "RECEIVABLE"}) String target, UUID receivableId,
                           BigDecimal amount, Instant allocatedAt, UUID allocatedBy, UUID reversesAllocationId,
                           String note) {
    }

    /** A refund asked of the order's money; PENDING until the accountant pays it out. */
    public record Refund(UUID refundId, UUID paymentId, UUID allocationId, BigDecimal amount, String currency,
                         String status, String source, Instant createdAt) {
    }
}

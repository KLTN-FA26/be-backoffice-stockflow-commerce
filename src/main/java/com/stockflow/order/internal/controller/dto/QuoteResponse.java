package com.stockflow.order.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A quote as the back office and the portal show it.
 *
 * @param status        as of now: a sent quote past its validity reads EXPIRED
 * @param version       to send back with the next change
 * @param acceptedTotal VND, once accepted
 */
public record QuoteResponse(UUID id, String quoteNumber, UUID customerId, String status, int revision,
                            String paymentTerm, BigDecimal depositPercent, Instant validUntil, String terms,
                            List<Line> lines, boolean priced, BigDecimal proposedTotal, BigDecimal acceptedTotal,
                            Instant sentAt,
                            UUID sentBy,
                            Instant respondedAt, UUID respondedBy, String customerFeedback, String cancellationReason,
                            Order convertedOrder, Instant convertedAt, List<Transition> history, Instant createdAt,
                            String createdBy, long version) {

    /**
     * @param moq    the minimum order quantity, where the lowest tier starts
     * @param sample whether acceptance passes the sample rule for this line, and why
     */
    public record Line(UUID id, int lineNo, String blankSku, UUID designSnapshotId, String printSpec, int quantity,
                       boolean priced, Integer moq, List<Tier> tiers, BigDecimal proposedUnitPrice,
                       BigDecimal proposedLineTotal,
                       Integer acceptedQuantity, BigDecimal acceptedUnitPrice, BigDecimal acceptedLineTotal,
                       Sample sample) {
    }

    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }

    public record Sample(boolean satisfied, UUID approvedSampleId, boolean reorder) {
    }

    public record Order(UUID orderId, String orderNumber, String status, BigDecimal total) {
    }

    public record Transition(int revision, String fromStatus, String toStatus, String reason, Instant changedAt,
                             UUID changedBy) {
    }
}

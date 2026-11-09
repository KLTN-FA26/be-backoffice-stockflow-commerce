package com.stockflow.order.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A quote as the back office and the portal show it.
 *
 * @param status        as of now: a sent quote past its validity reads EXPIRED
 * @param legacy        drafted before 4B-2 on blanks and designs: kept to read, not sent, accepted or converted (QD-16)
 * @param version       to send back with the next change
 * @param acceptedTotal VND, once accepted
 */
public record QuoteResponse(UUID id, String quoteNumber, UUID customerId, String status, int revision,
                            String paymentTerm, BigDecimal depositPercent, Instant validUntil, String terms,
                            java.time.LocalDate requestedDeliveryDate, boolean legacy, List<Line> lines, boolean priced,
                            BigDecimal proposedTotal, BigDecimal acceptedTotal,
                            Instant sentAt,
                            UUID sentBy,
                            Instant respondedAt, UUID respondedBy, String customerFeedback, String cancellationReason,
                            Order convertedOrder, Instant convertedAt, List<Transition> history, Instant createdAt,
                            String createdBy, long version) {

    /**
     * A line: cartons of the customer's own SKU, priced per piece (kltn-docs 14 BR-08). Ordered by the carton, shown
     * with its pieces: {@code units} = {@code cartons} x {@code unitsPerCarton}.
     *
     * @param measure          {@code CARTON}; {@code UNIT} on a legacy line, which names a blank ({@code sku}), its
     *                         design and print specification, and counts units
     * @param moq              where the lowest tier starts, in the line's measure
     * @param proposedUnitPrice VND per piece at the proposed cartons
     */
    public record Line(UUID id, int lineNo, String measure, String sku, Integer cartons, Integer unitsPerCarton,
                       int units, UUID designSnapshotId, String printSpec, boolean priced, Integer moq,
                       List<Tier> tiers, BigDecimal proposedUnitPrice, BigDecimal proposedLineTotal,
                       Integer acceptedCartons, Integer acceptedUnits, BigDecimal acceptedUnitPrice,
                       BigDecimal acceptedLineTotal) {
    }

    /** From {@code minQuantity} cartons (units on a legacy line) up, {@code unitPrice} VND per piece. */
    public record Tier(int minQuantity, BigDecimal unitPrice) {
    }

    public record Order(UUID orderId, String orderNumber, String status, BigDecimal total) {
    }

    public record Transition(int revision, String fromStatus, String toStatus, String reason, Instant changedAt,
                             UUID changedBy) {
    }
}

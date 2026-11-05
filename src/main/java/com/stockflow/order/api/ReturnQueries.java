package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Customers' return requests for other modules to show, read through the back-office list the returns screen reads
 * ({@code GET /api/v1/returns}). The back-office assistant (ADR-0010) answers with it what Sales, the warehouse, QC
 * and accounting ask about returns - which wait for review, which parcels are due back and by when, which wait for a
 * second person to settle - that no other {@code api} could: returns are otherwise this module's alone.
 *
 * <p><b>Scope.</b> The rules are that screen's: every customer's returns, so a portal customer is refused here, as the
 * screen's {@code ScopeGuards.requireBackOffice} refuses them, and staff limited to warehouses see
 * the returns of orders their warehouses ship (BR-SEC-002). Call it inside that endpoint's guard,
 * {@code @RequiresPermission(resource = "sales-rmas", action = READ, scope = ALL)}; outside a guard no scope is in
 * force and every warehouse is seen, as by the system.</p>
 *
 * <p>Read only. Nothing here approves, receives, inspects or settles anything.</p>
 */
public interface ReturnQueries {

    /** The most returns one call returns. */
    int MAX_RETURNS = 50;

    /**
     * Every status, in the order a return moves through them (docs 03 §12): opened and waiting for Sales' review
     * (REQUESTED), approved and waiting for the goods by a deadline (AWAITING_RETURN), counted into quarantine
     * (RECEIVED), decided unit by unit by QC (INSPECTED), its settlement waiting for a second person
     * (SETTLEMENT_PENDING), then RESOLVED; REJECTED at review or inspection, CANCELLED before the goods came back, or
     * EXPIRED when they did not come by the deadline.
     */
    List<String> STATUSES = List.of("REQUESTED", "AWAITING_RETURN", "RECEIVED", "INSPECTED", "SETTLEMENT_PENDING",
            "RESOLVED", "REJECTED", "CANCELLED", "EXPIRED");

    /** The returns still being worked on, which hold their units and keep the order from completing (BR-ORD-005). */
    List<String> OPEN_STATUSES = List.of("REQUESTED", "AWAITING_RETURN", "RECEIVED", "INSPECTED",
            "SETTLEMENT_PENDING");

    /**
     * The returns that match.
     *
     * @param statuses      any of {@link #STATUSES}; empty for every status
     * @param customerId    one customer's; null for every customer
     * @param number        text the RMA number or the order number contains; null or blank for any
     * @param deadlineFirst the nearest return deadline first, returns without one last; otherwise the most recently
     *                      requested first, as the screen lists them
     * @param limit         1..{@value #MAX_RETURNS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    PageResponse<ReturnSummary> returns(List<String> statuses, UUID customerId, String number, boolean deadlineFirst,
                                        int limit);

    /**
     * One return as the list shows it, without its lines and photos.
     *
     * @param status              one of {@link #STATUSES}
     * @param reason              PRINT_DEFECT, DESIGN_MISMATCH, DAMAGED_IN_TRANSIT, WRONG_ITEM, SHORT_DELIVERY...
     * @param requestedResolution what the customer asked for: REPLACEMENT, REFUND or CREDIT_NOTE
     * @param channel             where it was opened: PORTAL by the customer, BACK_OFFICE by Sales for them
     * @param returnDeadline      the start of the first day too late for the approved goods to come back (the day
     *                            after the last one); null before approval, or when the goods stay with the customer
     * @param settlementAmount    what the settlement makes good, in {@code currency}; null before one is proposed
     * @param closedAt            when it reached RESOLVED, REJECTED, CANCELLED or EXPIRED; null while open
     */
    record ReturnSummary(String rmaNumber, String orderNumber, UUID customerId, String status, String reason,
                         String requestedResolution, String channel, Instant requestedAt, Instant returnDeadline,
                         BigDecimal settlementAmount, BigDecimal refundedAmount, BigDecimal creditedAmount,
                         String currency, Instant closedAt) {
    }
}

package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Production orders for another module to show, as the production orders screen lists them
 * ({@code GET /api/v1/production-orders}, 4A-2, kltn-docs 17 §4.3, §5.3), named instead of by id.
 *
 * <p>The screen names a production order's sales order, delivery batch, producer and warehouse by id, which nobody
 * outside it can read; the back-office assistant (ADR-0011) is asked "LSX của đơn SO-… tới đâu rồi?" and "nhà sản xuất
 * nào đang trễ?". These are the same rows, with the order number, the batch's number, the producer's name and lead
 * time and the warehouse's code read beside them, and what the print sample rounds and the receipts have done so
 * far.</p>
 *
 * <p><b>Scope.</b> The screen's rule: a warehouse-bound caller sees only the production orders received at their
 * warehouses (BR-SEC-002). Call it inside that endpoint's guard,
 * {@code @RequiresPermission(resource = "production-orders", action = READ)}; outside a guard no scope is in force and
 * every warehouse is seen, as by the system.</p>
 *
 * <p>Read only. Nothing here draws up, approves, sends or receives anything.</p>
 */
public interface ProductionOrderQueries {

    /** The most production orders one call returns. */
    int MAX_ORDERS = 50;

    /**
     * Every status, in the order a production order moves through them (17 §5.3, {@code ck_production_order_status}):
     * drawn up, waiting for the Sales Manager's approval, sent to the producer, accepted by it, its print sample
     * waiting for the customer, mass printing, part of it received, all of it received; or cancelled before mass
     * printing, closed after it.
     */
    List<String> STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "SENT", "ACCEPTED", "SAMPLE_PENDING",
            "IN_PRODUCTION", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED", "CLOSED");

    /** Still to bring goods: everything but RECEIVED, CANCELLED and CLOSED ({@code ProductionOrderStatus.open}). */
    List<String> OPEN_STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "SENT", "ACCEPTED", "SAMPLE_PENDING",
            "IN_PRODUCTION", "PARTIALLY_RECEIVED");

    /**
     * The production orders that match, the most recently drawn up first, as the screen lists them.
     *
     * @param statuses         any of {@link #STATUSES}; empty for every status
     * @param salesOrderNumber the sales order's number exactly, case aside: its production orders only; null or blank
     *                         for every order (the screen's {@code salesOrderId} filter, reached by the number)
     * @param code             text the production order's code contains ({@code PRD-20261110-0001}); null or blank
     *                         for any
     * @param limit            1..{@value #MAX_ORDERS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    PageResponse<ProductionOrderRow> productionOrders(Collection<String> statuses, String salesOrderNumber,
                                                      String code, int limit);

    /**
     * Every production order of one sales order the caller may see, oldest first - what the order's own page shows
     * ({@code ProductionOrderService.ofSalesOrder}), under the same warehouse rule. At most {@value #MAX_ORDERS}.
     */
    List<ProductionOrderRow> ofSalesOrder(UUID salesOrderId);

    /**
     * The statuses in which a production order is with its producer and still owes goods (17 §4.12: "Sent →
     * Partially Received", {@code DelayAlertEvaluator.LATE_STATUSES}): the only ones a coordinator can chase.
     */
    List<String> WITH_PRODUCER_STATUSES = List.of("SENT", "ACCEPTED", "SAMPLE_PENDING", "IN_PRODUCTION",
            "PARTIALLY_RECEIVED");

    /**
     * The production orders the Production Coordinator should chase today (the assistant's follow-up list, its work
     * queue's late section): with the producer ({@link #WITH_PRODUCER_STATUSES}) and late, at risk, or - when
     * {@code dueWithinDays} is given - due within that many days. The most urgent first: late by need-by day, then the
     * producer's own day passed, then at risk, then due soon; within each, the earliest need-by day. Under the
     * screen's warehouse rule, as {@link #productionOrders}. Exists because the producers are outside the system:
     * chasing them is a phone call or a Zalo message, and the coordinator needs the list of whom to call, worked out
     * by the same rules as the delay alerts ({@code DelayAlertEvaluator}) but on today's dates rather than on alerts
     * last recomputed overnight.
     *
     * @param today         the business day the rules are read on
     * @param dueWithinDays also the orders whose expected completion day (its need-by day while it has none) is
     *                      within this many days from today; null for late and at risk only
     * @param warehouseId   only those received at this warehouse; null for every warehouse the caller may see. One the
     *                      caller may not see matches nothing
     * @param limit         1..{@value #MAX_ORDERS}; {@code total} of the page counts every match
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a limit out of range or a
     *         negative {@code dueWithinDays}
     */
    PageResponse<FollowUp> followUps(LocalDate today, Integer dueWithinDays, UUID warehouseId, int limit);

    /**
     * One production order as the follow-up list shows it, whatever its status, under the same warehouse rule; empty
     * when there is none the caller may see (a production order of another warehouse is not revealed to exist).
     */
    java.util.Optional<FollowUp> followUp(UUID productionOrderId, LocalDate today);

    /**
     * Why a production order is on the follow-up list, the most urgent reason first.
     * {@code LATE}: its need-by day (the first day of its batch's window) has passed and it is not received in full
     * (the PRODUCTION_LATE alert). {@code PRODUCER_OVERDUE}: the day the producer gave has passed, its need-by day not
     * yet. {@code AT_RISK}: the producer's day is after the need-by day (the AT_RISK alert). {@code DUE_SOON}: due
     * within the days asked. {@code NONE}: none of these (a single read of one that is not on the list).
     */
    enum FollowUpReason { LATE, PRODUCER_OVERDUE, AT_RISK, DUE_SOON, NONE }

    /**
     * A production order to chase, with what it holds up: one production order is drawn up per delivery batch (17
     * §4.3 step 6), so it holds up exactly that batch of that sales order's customer.
     *
     * @param order            the production order as {@link #productionOrders} lists it
     * @param customerId       the sales order's customer; null for an order without one
     * @param batchWindowStart the first day of the batch's delivery window; null when the batch has none or is no
     *                         longer in the schedule
     * @param batchWindowEnd   its last day; null with {@code batchWindowStart}
     * @param reason           why it is on the list
     * @param daysLate         days past its need-by day for LATE, past the producer's day for PRODUCER_OVERDUE; null
     *                         otherwise
     */
    record FollowUp(ProductionOrderRow order, UUID customerId, LocalDate batchWindowStart, LocalDate batchWindowEnd,
                    FollowUpReason reason, Integer daysLate) {
    }

    /**
     * One production order.
     *
     * @param status                 one of {@link #STATUSES}
     * @param salesOrderNumber       the sales order it makes goods for
     * @param deliveryBatchSeq       the number of the delivery batch it is drawn up for (one production order per
     *                               batch, 17 §4.3 step 6); null when the batch is no longer in the schedule
     * @param producerLeadDays       the producer's standard lead time in days, from sending to goods at the warehouse
     * @param receivingWarehouseCode where its goods are received; null when the warehouse is gone
     * @param needByDate             the first day of its batch's window (BR-26): when the goods are needed
     * @param expectedCompletionDate the day the producer expects the goods done, from its acceptance on; null before
     * @param sentAt                 when it was sent to the producer, on approval; null before
     * @param productionStartedAt    when mass printing started; null before
     * @param sampleApprovalRequired a print sample must be approved by the customer before mass printing (BR-24)
     * @param sampleRounds           how many print sample rounds were opened
     * @param latestSampleRound      the latest round; null when none was opened
     * @param lines                  what it orders, per SKU, and what receipts confirmed so far
     */
    record ProductionOrderRow(UUID id, String code, String status, UUID salesOrderId, String salesOrderNumber,
                              Integer deliveryBatchSeq, String producerName, int producerLeadDays,
                              String receivingWarehouseCode, LocalDate needByDate, LocalDate expectedCompletionDate,
                              Instant sentAt, Instant productionStartedAt, boolean sampleApprovalRequired,
                              int sampleRounds, SampleRound latestSampleRound, List<Line> lines) {

        public ProductionOrderRow {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** Cartons ordered on every line. */
        public int cartons() {
            return lines.stream().mapToInt(Line::cartons).sum();
        }

        /** Cartons confirmed received on every line, drafts of receipts not counted. */
        public int receivedCartons() {
            return lines.stream().mapToInt(Line::receivedCartons).sum();
        }
    }

    /**
     * One line: {@code cartons} of {@code unitsPerCarton} each, {@code receivedCartons} of them counted in by receipts
     * confirmed so far.
     */
    record Line(String sku, int cartons, int unitsPerCarton, int receivedCartons) {
    }

    /**
     * A print sample round (17 §4.3 steps 9-10).
     *
     * @param result {@code APPROVED}, {@code REPRINT_REQUESTED}, or null while it waits for the customer's answer
     */
    record SampleRound(int roundNo, String result, Instant openedAt, Instant answeredAt) {
    }
}

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

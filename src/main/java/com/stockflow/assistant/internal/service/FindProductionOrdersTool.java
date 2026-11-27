package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.api.ProductionOrderQueries;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The production orders screen as a list ({@code GET /api/v1/production-orders}, 4A-2, kltn-docs 17 §4.3, §5.3):
 * "LSX của đơn SO-… tới đâu rồi?", "lệnh sản xuất nào đang trễ?", "nhà sản xuất đã giao đủ chưa?". Since the rescope
 * printing is done by producers outside the system, one production order per delivery batch, and the order waits
 * AWAITING_STOCK while it is made; the sales order's own status does not say how far the producer is, so this is where
 * the answer is.
 *
 * <p><b>Same rules as the screen.</b> The rows come from {@link ProductionOrderQueries}, which applies the screen's
 * warehouse rule (a warehouse-bound caller sees the production orders received at their warehouses), and the screen's
 * controller refuses a portal account ({@code ScopeGuards.requireBackOffice}), so this tool does too.</p>
 *
 * <p><b>No more of the sales order than the screen shows.</b> The production orders screen names its sales order by
 * id. A role granted production orders without {@code sales-orders:READ} (none of the seeded ones since QD14 gave the
 * Sales Manager the orders; a custom role may still be one) has no screen telling it which order numbers exist: the
 * order number comes back only to a caller who may read orders, or to one who typed it to filter by it. The customer, the price and the print files are never returned:
 * none of it says how far the work is.</p>
 *
 * <p><b>Late, worked out here.</b> A small model compares dates badly, and "which are late?" is the question asked.
 * {@code overdue} (still open and the need-by day has passed, in Vietnam) and {@code atRisk} (the producer expects
 * the goods after the need-by day, the AT_RISK of 4A-7) are two comparisons of the row's own dates, made once here.</p>
 */
@Component
class FindProductionOrdersTool implements AssistantTool {

    /**
     * Fewer than the api's {@value ProductionOrderQueries#MAX_ORDERS}: a row carries dates, flags and SKUs, and thirty
     * of the longest still fit well under the result limit, where fifty would not.
     */
    static final int MAX_ROWS = 30;
    /** A producer's registered name can be long; thirty rows of it must still fit in one result. */
    static final int MAX_PRODUCER_NAME = 40;
    /** The SKUs of one production order, joined; a longer list is cut with an ellipsis. */
    static final int MAX_SKUS = 3;

    /** OPEN and ANY, then every status of the api, so the two cannot drift apart. */
    static final List<String> STATUSES = Stream.concat(Stream.of("OPEN", "ANY"),
            ProductionOrderQueries.STATUSES.stream()).toList();

    /** An order number is 18 characters and a production order code 17; the rest is room for a typo. */
    private static final int MAX_NUMBER = 32;

    private static final ToolSpec SPEC = new ToolSpec("findProductionOrders",
            "Production orders (lệnh sản xuất): printed goods ordered from an outside producer for one delivery batch "
                    + "of a customer order, most recently drawn up first. Each row: code, status, the customer order "
                    + "number (salesOrderNumber, only for a user who may read orders or who filtered by it), "
                    + "deliveryBatchSeq, the producer and its standard lead time in days (producerLeadDays), the "
                    + "warehouse it is received at, needByDate (when the batch needs the goods), "
                    + "expectedCompletionDate (the producer's own expected day, from its acceptance on), overdue "
                    + "(still open and needByDate has passed), atRisk (still open and the producer expects the goods "
                    + "after needByDate), cartons ordered and receivedCartons confirmed so "
                    + "far, the SKUs, and the print sample: sampleRequired, sampleRounds and sampleStatus of the "
                    + "latest round (WAITING for the customer's answer, APPROVED, REPRINT_REQUESTED). status: DRAFT "
                    + "being drawn up; PENDING_APPROVAL waiting for the Sales Manager; SENT to the producer; ACCEPTED "
                    + "by the producer; SAMPLE_PENDING its print sample waits for the customer; IN_PRODUCTION mass "
                    + "printing; PARTIALLY_RECEIVED part of it received; RECEIVED all received; CANCELLED before mass "
                    + "printing; CLOSED after it, short. OPEN asks for every status still to bring goods (all but "
                    + "RECEIVED, CANCELLED and CLOSED). Filter by status, by the customer order number and by text in "
                    + "the code; there is no filter by producer, warehouse or SKU and no filter on lateness, so for "
                    + "such a question use only a list that is not truncated, and otherwise say what the rows cover. "
                    + "total counts every match; when it is above the rows returned (truncated), say how many were "
                    + "left out. A user limited to particular warehouses sees only the production orders received at "
                    + "theirs.",
            ToolSchema.object()
                    .choice("status", "Only production orders in this status; OPEN for every one still to bring goods, "
                            + "ANY for every status. For one customer order use ANY, so finished ones are listed "
                            + "too", STATUSES)
                    .string("salesOrderNumber", "A customer order number exactly as shown on screen, for its "
                            + "production orders; an empty string for every order")
                    .string("code", "Text in the production order code (PRD-yyyyMMdd-nnnn), or part of it; an empty "
                            + "string for every code")
                    .integer("limit", "How many production orders to return, 1 to " + MAX_ROWS)
                    .build());

    private final ProductionOrderQueries productionOrders;
    private final CurrentUserProvider currentUsers;
    private final Clock clock;

    FindProductionOrdersTool(ProductionOrderQueries productionOrders, CurrentUserProvider currentUsers, Clock clock) {
        this.productionOrders = productionOrders;
        this.currentUsers = currentUsers;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/production-orders";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.PRODUCTION_ORDERS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        Optional<CurrentUser> caller = currentUsers.current();
        caller.ifPresent(ScopeGuards::requireBackOffice);
        String status = arguments.choice("status", STATUSES);
        String salesOrderNumber = arguments.optionalText("salesOrderNumber", MAX_NUMBER);
        String code = arguments.optionalText("code", MAX_NUMBER);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        List<String> statuses = switch (status) {
            case "OPEN" -> ProductionOrderQueries.OPEN_STATUSES;
            case "ANY" -> List.of();
            default -> List.of(status);
        };
        boolean showsOrderNumbers = salesOrderNumber != null
                || caller.map(user -> user.can(ToolPermissions.SALES_ORDERS, Action.READ)).orElse(false);
        LocalDate today = BusinessDate.of(clock.instant());
        PageResponse<ProductionOrderQueries.ProductionOrderRow> page = productionOrders.productionOrders(statuses,
                salesOrderNumber, code, limit);
        List<Row> rows = page.items().stream().map(row -> Row.of(row, showsOrderNumbers, today)).toList();
        return new Answer(rows, page.totalElements(), page.totalElements() > rows.size());
    }

    /**
     * @param total     every production order that matches among those the user may see
     * @param truncated {@code total} is above the rows returned
     */
    record Answer(List<Row> productionOrders, long total, boolean truncated) {
    }

    /**
     * @param salesOrderNumber null for a caller who may not read orders and did not type it
     * @param producerName     as registered, cut to {@value #MAX_PRODUCER_NAME} characters and an ellipsis
     * @param overdue          still to bring goods and its need-by day is before today in Vietnam
     * @param atRisk           still to bring goods and the producer's expected day is after the need-by day (4A-7
     *                         AT_RISK)
     * @param cartons          ordered on every line; {@code receivedCartons} confirmed by receipts so far
     * @param skus             the SKUs it prints, joined; the first {@value #MAX_SKUS} and an ellipsis when more
     * @param sampleStatus     the latest print sample round: WAITING, APPROVED or REPRINT_REQUESTED; null when no
     *                         round was opened
     */
    record Row(String code, String status, String salesOrderNumber, Integer deliveryBatchSeq, String producerName,
               int producerLeadDays, String warehouseCode, LocalDate needByDate, LocalDate expectedCompletionDate,
               boolean overdue, boolean atRisk, int cartons, int receivedCartons, String skus, boolean sampleRequired, int sampleRounds, String sampleStatus) {

        static Row of(ProductionOrderQueries.ProductionOrderRow row, boolean showsOrderNumber, LocalDate today) {
            String producer = row.producerName();
            boolean open = ProductionOrderQueries.OPEN_STATUSES.contains(row.status());
            ProductionOrderQueries.SampleRound latest = row.latestSampleRound();
            List<String> skus = row.lines().stream().map(ProductionOrderQueries.Line::sku).distinct().toList();
            return new Row(row.code(), row.status(), showsOrderNumber ? row.salesOrderNumber() : null,
                    row.deliveryBatchSeq(),
                    producer != null && producer.length() > MAX_PRODUCER_NAME
                            ? producer.substring(0, MAX_PRODUCER_NAME) + "…" : producer,
                    row.producerLeadDays(), row.receivingWarehouseCode(), row.needByDate(),
                    row.expectedCompletionDate(),
                    open && row.needByDate() != null && row.needByDate().isBefore(today),
                    open && row.expectedCompletionDate() != null && row.needByDate() != null
                            && row.expectedCompletionDate().isAfter(row.needByDate()),
                    row.cartons(), row.receivedCartons(),
                    skus.isEmpty() ? null : skus.stream().limit(MAX_SKUS).collect(Collectors.joining(", "))
                            + (skus.size() > MAX_SKUS ? ", …" : ""),
                    row.sampleApprovalRequired(), row.sampleRounds(),
                    latest == null ? null : latest.result() == null ? "WAITING" : latest.result());
        }
    }
}

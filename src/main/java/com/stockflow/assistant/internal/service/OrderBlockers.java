package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.web.CorrelationIdFilter;
import com.stockflow.fulfillment.api.OrderPicking;
import com.stockflow.fulfillment.api.OrderPickingQueries;
import com.stockflow.order.api.HandoverBlock;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStockCoverage;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ProductionOrderQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Why a live order does not move on, from what the {@code api}s already decide, never inferred: the one reading of an
 * order's blockers, shared by {@link FindOrderTool} (the model's answer) and {@link OrderNextSteps} (the order screen's
 * "why is this order not moving" panel), so the two can never tell a user different reasons for the same order.
 *
 * <p>The checks: the contract not signed yet, the on-signing instalment awaited (PENDING_PAYMENT), the hold, the
 * handover check ({@link OrderService#handoverBlocks}: the batch's instalment, its delivery window) for an order at the
 * dispatch stage, the production orders not received yet ({@link ProductionOrderQueries}, under the production
 * screen's permission and warehouse rule), and the cartons not held at the shipping warehouse for the earliest delivery
 * batch still short ({@link OrderStockCoverage}, under the coverage screen's permission). A check the caller may not
 * see, or that fails, is named in {@code notChecked} instead of being left silent: an empty list of reasons must never
 * read as "nothing is wrong" when half of it was not looked at. A failure is logged and leaves the rest whole.</p>
 *
 * <p><b>Two steps no status names.</b> An order whose stock is all held does not move on by itself: the warehouse
 * manager releases it ({@code OrderReleaseService}, {@code sales-order-releases:UPDATE}) - a CONFIRMED or AWAITING_STOCK
 * order whose batch to release is held in full, or a PARTIALLY_FULFILLED one whose next batch is ({@code
 * AWAITING_RELEASE}, from {@link OrderStockCoverage.Coverage#releasableBatch}). A released order, READY_TO_FULFILL,
 * waits for the warehouse to open its pick task and pick it ({@code AWAITING_PICK}): opening the task moves it to
 * PICKING, so READY_TO_FULFILL means no task yet - or, for an order picked before its release, a task still being
 * worked, whose status is given to a caller who may see it on the pick-lines screen ({@link OrderPickingQueries}).
 * Without these, such an order read as "moving normally" while it stood still.</p>
 *
 * <p><b>Every reason of the handover check.</b> {@link HandoverBlock#reasonCodes} carries each reason the goods may not
 * leave now, in the order they are checked; a batch whose instalment is unpaid <i>and</i> whose window has not opened
 * gets both, because paying alone would not let it leave. Reading only {@link HandoverBlock#reasonCode} told Sales to
 * chase a payment that would not have released the goods.</p>
 */
@Component
class OrderBlockers {

    private static final Logger log = LoggerFactory.getLogger(OrderBlockers.class);

    /** Reasons of one order: one per production order and per short line can add up on a long schedule. */
    static final int MAX_BLOCKERS = 20;

    /** What {@code notChecked} names. */
    static final String HANDOVER = "HANDOVER";
    static final String PRODUCTION_ORDERS = "PRODUCTION_ORDERS";
    static final String STOCK_HELD = "STOCK_HELD";

    /** The error codes of the handover check ({@code Order.requireHandoverAllowed}) that a blocker names itself. */
    static final String BALANCE_NOT_PAID = "ORDER_BALANCE_NOT_PAID";
    static final String WINDOW_NOT_OPEN = "DELIVERY_BATCH_WINDOW_NOT_OPEN";

    /** Not handed over whole yet: an order that can still be held up on its way to the customer. */
    static final Set<OrderStatus> LIVE = EnumSet.of(OrderStatus.DRAFT, OrderStatus.PENDING_CONTRACT,
            OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK, OrderStatus.IN_PRODUCTION,
            OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED,
            OrderStatus.ON_HOLD);
    /** The statuses the order module hands goods over from ({@code Order.requireHandoverAllowed}). */
    static final Set<OrderStatus> AT_HANDOVER = EnumSet.of(OrderStatus.PICKING, OrderStatus.PACKED,
            OrderStatus.PARTIALLY_FULFILLED);
    /** Before a signed contract there are no production orders, no shipping warehouse and nothing held. */
    static final Set<OrderStatus> UNSIGNED = EnumSet.of(OrderStatus.DRAFT, OrderStatus.PENDING_CONTRACT);
    /** The statuses an order is released from ({@code Order.release}, {@code Order.releaseLaterBatch}). */
    static final Set<OrderStatus> RELEASED_FROM = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK,
            OrderStatus.PARTIALLY_FULFILLED);

    private final OrderService orders;
    private final ProductionOrderQueries productionOrders;
    private final OrderStockCoverage stockCoverage;
    private final OrderPickingQueries picking;
    private final Clock clock;

    OrderBlockers(OrderService orders, ProductionOrderQueries productionOrders, OrderStockCoverage stockCoverage,
                  OrderPickingQueries picking, Clock clock) {
        this.orders = orders;
        this.productionOrders = productionOrders;
        this.stockCoverage = stockCoverage;
        this.picking = picking;
        this.clock = clock;
    }

    /**
     * The order numbered {@code number} as the order list shows it to {@code caller}: an exact number (the list matches
     * a substring of numbers, names and phones), and for a caller limited to warehouses only an order of one of theirs
     * ({@code OrderWarehouseVisibility.limitOf}, which {@code GET /api/v1/orders} applies). Out of reach is the same
     * 404 as unknown, so an order of another warehouse is not revealed to exist.
     *
     * @throws NotFoundException for no such order the caller may read
     */
    OrderSummary find(String number, Optional<CurrentUser> caller) {
        Set<UUID> limit = OrderReach.limitOf(caller);
        OrderSummary match = orders.list(new ListOrdersQuery(0, 20, number, null, null, null, null, null, limit))
                .items().stream()
                .filter(order -> order.orderNumber().equalsIgnoreCase(number))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Order", number));
        return orders.findById(match.orderId()).orElseThrow(() -> new NotFoundException("Order", number));
    }

    /**
     * Why a live order does not move on; null for an order past that point. Each check runs only for the statuses it
     * can apply to, so a CONFIRMED order is never told it "may not be handed over" merely because it is not packed yet.
     *
     * @param seesInstallments the caller may read the contract's instalments: only then does an INSTALLMENT_DUE
     *                         reason carry its amount
     */
    Blockers of(OrderSummary order, Optional<CurrentUser> caller, boolean seesInstallments) {
        OrderStatus status = order.status();
        if (!LIVE.contains(status)) {
            return null;
        }
        List<Blocker> reasons = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();
        OrderSummary.Lifecycle life = order.lifecycle();
        if (UNSIGNED.contains(status)) {
            reasons.add(Blocker.of("CONTRACT_NOT_SIGNED"));
        }
        if (status == OrderStatus.PENDING_PAYMENT) {
            reasons.add(Blocker.of("SIGNING_INSTALLMENT_UNPAID"));
        }
        if (life != null && life.holdReason() != null) {
            reasons.add(Blocker.of("ON_HOLD").holdReason(life.holdReason().name()));
        }
        if (AT_HANDOVER.contains(status)) {
            handoverBlockers(order, seesInstallments, reasons, notChecked);
        }
        if (status == OrderStatus.READY_TO_FULFILL) {
            reasons.add(Blocker.of("AWAITING_PICK").pickTaskStatus(pickTaskStatus(order, caller)));
        }
        if (!UNSIGNED.contains(status)) {
            if (status != OrderStatus.PENDING_PAYMENT) {
                if (may(caller, ToolPermissions.SHIPPING_WAREHOUSES)) {
                    stockBlockers(order, reasons, notChecked);
                } else {
                    notChecked.add(STOCK_HELD);
                }
            }
            if (may(caller, ToolPermissions.PRODUCTION_ORDERS)) {
                productionBlockers(order, reasons, notChecked);
            } else {
                notChecked.add(PRODUCTION_ORDERS);
            }
        }
        return new Blockers(reasons.stream().limit(MAX_BLOCKERS).toList(), reasons.size() > MAX_BLOCKERS,
                List.copyOf(notChecked));
    }

    static boolean may(Optional<CurrentUser> caller, String resource) {
        return caller.map(user -> user.can(resource, Action.READ)).orElse(false);
    }

    /**
     * The order module's own handover check, for the delivery batch on its way out: what a vehicle would be refused,
     * one reason per code the check gives ({@link HandoverBlock#reasonCodes}).
     */
    private void handoverBlockers(OrderSummary order, boolean seesInstallments, List<Blocker> reasons,
                                  List<String> notChecked) {
        try {
            for (HandoverBlock block : orders.handoverBlocks(List.of(order.orderId()), clock.instant())) {
                if (!order.orderId().equals(block.orderId())) {
                    continue;
                }
                List<String> codes = !block.reasonCodes().isEmpty() ? block.reasonCodes()
                        : block.reasonCode() == null ? List.of("") : List.of(block.reasonCode());
                for (String code : codes) {
                    Blocker blocker = switch (code == null ? "" : code) {
                        case BALANCE_NOT_PAID -> Blocker.of("INSTALLMENT_DUE")
                                .amountDue(seesInstallments ? block.amountDue() : null,
                                        seesInstallments ? block.currency() : null);
                        case WINDOW_NOT_OPEN -> Blocker.of("DELIVERY_WINDOW_NOT_OPEN").windowStart(block.windowStart());
                        default -> Blocker.of("HANDOVER_REFUSED").refusalCode(code);
                    };
                    reasons.add(blocker.batchSeq(block.batchSeq()));
                }
            }
        } catch (RuntimeException failure) {
            leftOut(order, HANDOVER, failure);
            notChecked.add(HANDOVER);
        }
    }

    /**
     * The shipping warehouse not chosen yet, or the lines of the earliest delivery batch not fully held there: batches
     * are released in order, so the earliest one short is the one the order waits for.
     */
    private void stockBlockers(OrderSummary order, List<Blocker> reasons, List<String> notChecked) {
        try {
            Optional<OrderStockCoverage.Coverage> found = stockCoverage.coverage(order.orderId());
            if (found.isEmpty()) {
                return;
            }
            OrderStockCoverage.Coverage coverage = found.get();
            if (!coverage.warehouseChosen()) {
                reasons.add(Blocker.of("NO_SHIPPING_WAREHOUSE"));
                return;
            }
            awaitingRelease(order, coverage).ifPresent(reasons::add);
            Optional<OrderStockCoverage.Batch> shortBatch = coverage.batches().stream()
                    .filter(batch -> !batch.fullyHeld()).findFirst();
            List<OrderStockCoverage.Line> lines = shortBatch.map(OrderStockCoverage.Batch::lines)
                    .orElse(coverage.batches().isEmpty() ? coverage.lines() : List.of());
            Integer seq = shortBatch.map(OrderStockCoverage.Batch::seq).orElse(null);
            lines.stream().filter(line -> line.shortCartons() > 0)
                    .forEach(line -> reasons.add(Blocker.of("STOCK_NOT_HELD").batchSeq(seq).stock(line)));
        } catch (RuntimeException failure) {
            leftOut(order, STOCK_HELD, failure);
            notChecked.add(STOCK_HELD);
        }
    }

    /**
     * The batch the warehouse may release now, held in full and not released: the batch the release would take next
     * ({@link OrderStockCoverage.Coverage#releasableBatch}), or, for an order without a schedule, the whole order not
     * released yet. Empty when nothing waits for a release.
     */
    static Optional<Blocker> awaitingRelease(OrderSummary order, OrderStockCoverage.Coverage coverage) {
        if (!RELEASED_FROM.contains(order.status())) {
            return Optional.empty();
        }
        if (coverage.batches().isEmpty()) {
            boolean waits = order.status() != OrderStatus.PARTIALLY_FULFILLED && order.releasedAt() == null
                    && coverage.fullyHeld();
            return waits ? Optional.of(Blocker.of("AWAITING_RELEASE")) : Optional.empty();
        }
        Integer next = coverage.releasableBatch();
        return next == null ? Optional.empty() : coverage.batches().stream()
                .filter(batch -> batch.seq() == next && batch.fullyHeld())
                .findFirst()
                .map(batch -> Blocker.of("AWAITING_RELEASE").batchSeq(next));
    }

    /**
     * The status of a released order's pick task as the pick-lines screen shows it to the caller; null when they may
     * not open pick lists, when fulfillment's rule keeps the task from them, and when there is none - which the order's
     * status already says in the usual case (opening a task makes it PICKING).
     */
    private String pickTaskStatus(OrderSummary order, Optional<CurrentUser> caller) {
        if (caller.isEmpty() || !caller.get().can(ToolPermissions.PICK_LISTS, Action.READ)) {
            return null;
        }
        try {
            return picking.pickingOf(order.orderId(), caller.get()).map(OrderPicking::status).orElse(null);
        } catch (RuntimeException failure) {
            boolean hidden = failure instanceof BusinessException refused
                    && GetOrderPickingTool.TASK_HIDDEN.contains(refused.errorCode());
            if (!hidden) {
                leftOut(order, "PICK_TASK", failure);
            }
            return null;
        }
    }

    /** The production orders of the order still to bring goods, among those the production screen shows the caller. */
    private void productionBlockers(OrderSummary order, List<Blocker> reasons, List<String> notChecked) {
        try {
            productionOrders.ofSalesOrder(order.orderId()).stream()
                    .filter(production -> ProductionOrderQueries.OPEN_STATUSES.contains(production.status()))
                    .forEach(production -> reasons.add(Blocker.of("PRODUCTION_NOT_RECEIVED")
                            .batchSeq(production.deliveryBatchSeq()).production(production)));
        } catch (RuntimeException failure) {
            leftOut(order, PRODUCTION_ORDERS, failure);
            notChecked.add(PRODUCTION_ORDERS);
        }
    }

    /** A check that failed leaves its part out, logged as a failed tool is, instead of taking the answer down. */
    private static void leftOut(OrderSummary order, String what, RuntimeException failure) {
        log.error("Could not check {} of order {} (correlation id {})", what, order.orderNumber(),
                MDC.get(CorrelationIdFilter.MDC_KEY), failure);
    }

    /**
     * @param reasons          at most {@value #MAX_BLOCKERS}, in the order the checks run: contract, payment on
     *                         signing, hold, handover, pick, release, stock, production
     * @param reasonsTruncated more reasons were found than are listed
     * @param notChecked       the checks this user may not see, or that could not be read: {@code HANDOVER},
     *                         {@code PRODUCTION_ORDERS}, {@code STOCK_HELD}
     */
    record Blockers(List<Blocker> reasons, boolean reasonsTruncated, List<String> notChecked) {
    }

    /**
     * One reason, by its code; only the fields of that code are set (nulls are left out of the result).
     *
     * @param amountDue   INSTALLMENT_DUE: what the batch's instalments still want before its goods leave; null for a
     *                    user who may not see instalments
     * @param batchSeq    the delivery batch concerned; null for an order without a delivery schedule
     * @param refusalCode HANDOVER_REFUSED: the handover check's own code
     * @param cartons     STOCK_NOT_HELD: cartons the batch's line needs; PRODUCTION_NOT_RECEIVED: cartons ordered
     * @param pickTaskStatus AWAITING_PICK: the status of the order's pick task, for a caller who may see it; null when
     *                       there is none or it is not theirs to see
     */
    record Blocker(String code, String holdReason, BigDecimal amountDue, String currency, Integer batchSeq,
                   LocalDate windowStart, String refusalCode, String sku, Integer cartons, Integer heldCartons,
                   Integer shortCartons, String productionOrderCode, String productionOrderStatus,
                   LocalDate needByDate, LocalDate expectedCompletionDate, Integer receivedCartons,
                   String pickTaskStatus) {

        static Blocker of(String code) {
            return new Blocker(code, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        Blocker holdReason(String reason) {
            return new Blocker(code, reason, amountDue, currency, batchSeq, windowStart, refusalCode, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker amountDue(BigDecimal amount, String inCurrency) {
            return new Blocker(code, holdReason, amount, inCurrency, batchSeq, windowStart, refusalCode, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker batchSeq(Integer seq) {
            return new Blocker(code, holdReason, amountDue, currency, seq, windowStart, refusalCode, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker windowStart(LocalDate start) {
            return new Blocker(code, holdReason, amountDue, currency, batchSeq, start, refusalCode, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker refusalCode(String refused) {
            return new Blocker(code, holdReason, amountDue, currency, batchSeq, windowStart, refused, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker stock(OrderStockCoverage.Line line) {
            return new Blocker(code, holdReason, amountDue, currency, batchSeq, windowStart, refusalCode, line.sku(),
                    line.cartons(), line.heldCartons(), line.shortCartons(), productionOrderCode,
                    productionOrderStatus, needByDate, expectedCompletionDate, receivedCartons, pickTaskStatus);
        }

        Blocker production(ProductionOrderQueries.ProductionOrderRow production) {
            return new Blocker(code, holdReason, amountDue, currency, batchSeq, windowStart, refusalCode, sku,
                    production.cartons(), heldCartons, shortCartons, production.code(), production.status(),
                    production.needByDate(), production.expectedCompletionDate(), production.receivedCartons(),
                    pickTaskStatus);
        }

        Blocker pickTaskStatus(String status) {
            return new Blocker(code, holdReason, amountDue, currency, batchSeq, windowStart, refusalCode, sku, cartons,
                    heldCartons, shortCartons, productionOrderCode, productionOrderStatus, needByDate,
                    expectedCompletionDate, receivedCartons, status);
        }
    }
}

package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * U3: one order by its number — where it stands, why it is held, and how it got there.
 *
 * <p><b>Extra guard.</b> {@code OrderService.findById} and {@code list} read no scope; {@code OrderController}
 * protects them with {@code ScopeGuards.requireBackOffice}, and so does this tool. The answer carries no contact,
 * phone, e-mail or address: none of it is needed to say where an order is, and it would leave the system (design
 * §5.5).</p>
 */
@Component
class FindOrderTool implements AssistantTool {

    static final int MAX_HISTORY = 30;

    private static final ToolSpec SPEC = new ToolSpec("findOrder",
            "One customer order by its order number: status, whether and why it is on hold (reason and note), the "
                    + "status before the hold, its lines (SKU and quantity), the dates it was placed, released, "
                    + "packed, shipped, delivered, completed or closed, and its status history with reasons. It "
                    + "does not return customer contact details. Ask the user for the order number if they did not "
                    + "give one.",
            ToolSchema.object()
                    .string("orderNumber", "The order number exactly as shown on screen")
                    .build());

    private final OrderService orders;
    private final CurrentUserProvider currentUsers;

    FindOrderTool(OrderService orders, CurrentUserProvider currentUsers) {
        this.orders = orders;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/orders";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.SALES_ORDERS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        currentUsers.current().ifPresent(ScopeGuards::requireBackOffice);
        String number = arguments.text("orderNumber", 32);
        // The list matches a substring of the number (and of names and phones); only an exact number is this order.
        OrderSummary match = orders.list(new ListOrdersQuery(0, 20, number, null, null, null, null, null)).items()
                .stream()
                .filter(order -> order.orderNumber().equalsIgnoreCase(number))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Order", number));
        OrderSummary order = orders.findById(match.orderId())
                .orElseThrow(() -> new NotFoundException("Order", number));
        List<OrderStatusChange> history = orders.history(order.orderId());
        OrderSummary.Lifecycle life = order.lifecycle();
        return new Answer(order.orderNumber(), order.status().name(),
                life == null || life.holdReason() == null ? null
                        : new Hold(life.holdReason().name(), life.holdNote(),
                        life.statusBeforeHold() == null ? null : life.statusBeforeHold().name()),
                order.lines().stream().map(line -> new Line(line.sku(), line.quantity())).toList(),
                new Dates(order.placedAt(), order.releasedAt(), life == null ? null : life.packedAt(),
                        life == null ? null : life.shippedAt(), life == null ? null : life.deliveredAt(),
                        life == null ? null : life.completedAt(), life == null ? null : life.closedAt()),
                history.stream().skip(Math.max(0, history.size() - MAX_HISTORY))
                        .map(change -> new Change(change.from() == null ? null : change.from().name(),
                                change.to().name(), change.reason(), change.occurredAt()))
                        .toList(),
                history.size() > MAX_HISTORY);
    }

    /**
     * @param hold             null when the order is not on hold
     * @param historyTruncated only the latest {@value #MAX_HISTORY} changes are listed
     */
    record Answer(String orderNumber, String status, Hold hold, List<Line> lines, Dates dates, List<Change> history,
                  boolean historyTruncated) {
    }

    record Hold(String reason, String note, String statusBeforeHold) {
    }

    record Line(String sku, int quantity) {
    }

    record Dates(Instant placedAt, Instant releasedAt, Instant packedAt, Instant shippedAt, Instant deliveredAt,
                 Instant completedAt, Instant closedAt) {
    }

    record Change(String from, String to, String reason, Instant at) {
    }
}

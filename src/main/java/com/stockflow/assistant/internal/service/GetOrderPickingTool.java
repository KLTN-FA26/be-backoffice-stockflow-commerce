package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.fulfillment.api.OrderPicking;
import com.stockflow.fulfillment.api.OrderPickingQueries;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * U3, the detail: an order's pick lines — which were short, by how much, where — behind "it is on hold for stock".
 *
 * <p><b>Extra guard.</b> The order number is resolved through {@code OrderService.list}, which reads no scope; like
 * {@code findOrder} the tool requires the back office first.</p>
 *
 * <p><b>No more of the order than the pick-lines screen shows.</b> That screen shows a task with its order number and
 * nothing else of the order, and nothing at all for an order with no task the caller may see. Under the seeded grants
 * warehouse managers and QC hold this tool's permission but not {@code sales-orders:READ}: no screen of theirs shows an
 * order's status or tells them which order numbers exist. So for a caller without {@code sales-orders:READ}, every case
 * without a visible task — no task yet, a task in another warehouse or not theirs to see, no order with that number —
 * gets one and the same note, with the number as they typed it. Only a caller who may read orders is told the status,
 * which is what says where an order without a task stands: a task opens when the warehouse assigns picking after
 * release, and for printed goods only once production is done, so "no task" alone does not mean "not released".</p>
 *
 * <p><b>Who may see a task</b> is fulfillment's rule ({@code TaskAccess}): its assignee, a packer once it waits at the
 * packing table, and whoever oversees tasks or re-verifies designs, in their own warehouses. {@code pickingOf} applies
 * the whole rule, so nothing is declared beside the screen's permission: warehouse staff, who may open the assistant
 * since V20261029000200 and hold neither oversight nor re-verification, are served their own tasks, and a task they
 * may not see gets the same note as an order without one.</p>
 */
@Component
class GetOrderPickingTool implements AssistantTool {

    /**
     * What fulfillment answers when the caller may not see a task: not theirs, or in another warehouse.
     * {@link FindOrderTool} reads the same set for its picking block.
     */
    static final Set<ErrorCode> TASK_HIDDEN = Set.of(ErrorCode.NOT_FOUND, ErrorCode.OUT_OF_DATA_SCOPE,
            ErrorCode.FORBIDDEN);

    private static final String NO_VISIBLE_TASK = "No pick task for this order number is visible to this user. Say "
            + "only that: their permissions do not show orders, so this tells neither whether the order exists nor "
            + "what its status is. A task appears once the warehouse assigns picking after the order is released, and "
            + "for printed goods only once production is done; findProductionOrders says how far printing is.";

    private static final ToolSpec SPEC = new ToolSpec("getOrderPicking",
            "The pick task of one customer order, looked up by the order number alone, with every pick line: SKU, "
                    + "location, lot, units allocated, picked, short (not found by the picker) and put back, and the "
                    + "line status (SHORT when units were missing). Call it directly, by the order number, for a "
                    + "question about picking an order: what was short and where, whether picking is done. When "
                    + "findOrder is offered too, its picking block already gives the task status and the lines still "
                    + "short: call this one only to see every line (allocated, picked, put back). Only tasks the user "
                    + "may see, in warehouses they may see; when there is none, the note says what can be told.",
            ToolSchema.object()
                    .string("orderNumber", "The order number exactly as shown on screen")
                    .build());

    private final OrderService orders;
    private final OrderPickingQueries picking;
    private final CurrentUserProvider currentUsers;

    GetOrderPickingTool(OrderService orders, OrderPickingQueries picking, CurrentUserProvider currentUsers) {
        this.orders = orders;
        this.picking = picking;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/fulfillment/tasks/{taskId}/pick-lines";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.PICK_LISTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        CurrentUser user = currentUsers.current()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        ScopeGuards.requireBackOffice(user);
        String number = arguments.text("orderNumber", 32);
        boolean readsOrders = user.can(ToolPermissions.SALES_ORDERS, Action.READ);
        Optional<OrderSummary> order = orders.list(new ListOrdersQuery(0, 20, number, null, null, null, null, null))
                .items().stream()
                .filter(candidate -> candidate.orderNumber().equalsIgnoreCase(number))
                .findFirst();
        if (order.isEmpty()) {
            if (readsOrders) {
                throw new NotFoundException("Order", number);
            }
            return Answer.noVisibleTask(number);
        }
        Optional<OrderPicking> task;
        try {
            task = picking.pickingOf(order.get().orderId(), user);
        } catch (BusinessException hidden) {
            if (readsOrders || !TASK_HIDDEN.contains(hidden.errorCode())) {
                throw hidden;
            }
            return Answer.noVisibleTask(number);
        }
        if (task.isPresent()) {
            return Answer.of(order.get(), task.get());
        }
        return readsOrders ? Answer.noTaskYet(order.get()) : Answer.noVisibleTask(number);
    }

    /** @param note why there are no lines, when there are none */
    record Answer(String orderNumber, String taskStatus, Instant assignedAt, Instant pickedAt, List<Line> lines,
                  String note) {

        static Answer of(OrderSummary order, OrderPicking task) {
            return new Answer(order.orderNumber(), task.status(), task.assignedAt(), task.pickedAt(),
                    task.lines().stream().map(Line::of).toList(), null);
        }

        /** For a caller who may read orders: the order's status says where it stands. */
        static Answer noTaskYet(OrderSummary order) {
            return new Answer(order.orderNumber(), null, null, null, List.of(), "The order (status "
                    + order.status().name() + ") has no pick task yet. A pick task is opened when the warehouse "
                    + "assigns picking after the order is released, and for printed goods only once production is "
                    + "done; use the order status to say where it stands.");
        }

        /** For a caller who may not read orders: the same answer whatever the reason, so it reveals nothing. */
        static Answer noVisibleTask(String typedNumber) {
            return new Answer(typedNumber, null, null, null, List.of(), NO_VISIBLE_TASK);
        }
    }

    record Line(String sku, String locationCode, String lotNumber, int allocated, int picked, int shortQuantity,
                int putBack, String status) {

        static Line of(OrderPicking.Line line) {
            return new Line(line.sku(), line.locationCode(), line.lotNumber(), line.allocatedQty(), line.pickedQty(),
                    line.shortQty(), line.putBackQty(), line.status());
        }
    }
}

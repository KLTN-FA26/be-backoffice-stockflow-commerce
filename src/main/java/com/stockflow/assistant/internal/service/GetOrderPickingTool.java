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

/**
 * U3, the detail: an order's pick lines — which were short, by how much, where — behind "it is on hold for stock".
 *
 * <p><b>Extra guard.</b> The order number is resolved through {@code OrderService.list}, which reads no scope; like
 * {@code findOrder} the tool requires the back office first. It returns nothing of the order but its number: the
 * pick-lines screen this tool mirrors shows the same task with its order number.</p>
 */
@Component
class GetOrderPickingTool implements AssistantTool {

    private static final ToolSpec SPEC = new ToolSpec("getOrderPicking",
            "The pick task of one customer order and every pick line: SKU, location, lot, units allocated, picked, "
                    + "short (not found by the picker) and put back, and the line status (SHORT when units were "
                    + "missing). Use it after findOrder when an order is on hold for stock, to say what exactly was "
                    + "short and where. Only tasks of warehouses the user may see.",
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
    @RequiresPermission(resource = ToolPermissions.PICK_LISTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        CurrentUser user = currentUsers.current()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        ScopeGuards.requireBackOffice(user);
        String number = arguments.text("orderNumber", 32);
        OrderSummary order = orders.list(new ListOrdersQuery(0, 20, number, null, null, null, null, null)).items()
                .stream()
                .filter(candidate -> candidate.orderNumber().equalsIgnoreCase(number))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Order", number));
        return picking.pickingOf(order.orderId(), user)
                .map(task -> new Answer(order.orderNumber(), task.status(), task.assignedAt(), task.pickedAt(),
                        task.lines().stream().map(Line::of).toList(), null))
                // Not "not released": a task opens only when the warehouse assigns picking after release, and a print
                // order's only once production is done. The order's own status is what says where it stands.
                .orElseGet(() -> new Answer(order.orderNumber(), null, null, null, List.of(),
                        "The order (status " + order.status().name() + ") has no pick task yet. A pick task is "
                                + "opened when the warehouse assigns picking after the order is released, and for "
                                + "printed goods only once production is done; use the order status to say where it "
                                + "stands."));
    }

    /** @param note why there are no lines, when there are none */
    record Answer(String orderNumber, String taskStatus, Instant assignedAt, Instant pickedAt, List<Line> lines,
                  String note) {
    }

    record Line(String sku, String locationCode, String lotNumber, int allocated, int picked, int shortQuantity,
                int putBack, String status) {

        static Line of(OrderPicking.Line line) {
            return new Line(line.sku(), line.locationCode(), line.lotNumber(), line.allocatedQty(), line.pickedQty(),
                    line.shortQty(), line.putBackQty(), line.status());
        }
    }
}

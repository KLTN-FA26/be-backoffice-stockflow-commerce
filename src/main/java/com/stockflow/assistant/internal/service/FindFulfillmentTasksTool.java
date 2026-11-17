package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ROLE-7: the pick-and-pack queue - "Kho còn bao nhiêu đơn chờ nhặt?", "Task nào nhặt thiếu?", "Tôi còn task nào?" -
 * as the pick-lists screen lists it ({@code GET /api/v1/fulfillment/tasks}), through the method that screen calls,
 * {@code FulfillmentService.listTasks}.
 *
 * <p><b>Who sees which task is fulfillment's rule, applied there once</b>, for the screen and for this tool alike:
 * whoever oversees tasks sees every task of their warehouses, and anyone else - a picker - only the tasks assigned to
 * them; a warehouse-bound user only in their own warehouses. So an empty list for a picker says nothing about the
 * warehouse. The answer says which applied ({@link Visibility}), read from the same permission as {@code listTasks}
 * reads it, because the model cannot tell a picker from a manager by a role name. The design check at packing went
 * with the rescope (V20261108000500): packing is now the carton check, which looks only at the SKU and the count. Unlike {@code getOrderPicking}, nothing more is
 * declared for the panel: everyone holding the screen's permission gets the tasks that rule gives them.</p>
 *
 * <p><b>What a row leaves out.</b> Who the task is assigned to (a person's id and name: only whether it is assigned,
 * and whether to the caller) and the order's lines. The order number is there because
 * the screen shows it; nothing else of the order is, so a role without {@code sales-orders:READ} learns no order's
 * state from it (design §5.8).</p>
 *
 * <p>A task names its warehouse by id; the code is looked up through {@code warehouse :: api}, once per warehouse in a
 * call.</p>
 */
@Component
class FindFulfillmentTasksTool implements AssistantTool {

    static final int MAX_ROWS = 50;

    /**
     * The pick statuses, as {@code FulfillmentTaskQuery} names them (the domain enum is fulfillment's own); ANY for no
     * filter. A status fulfillment adds later is refused there as {@code UNSUPPORTED_PARAMETER} until listed here.
     * Not SHORT: fulfillment declares it but never sets it. A short pick marks its line SHORT, the task stays PICKING
     * and the order goes on hold, so a filter on SHORT would always answer that no task is short.
     */
    static final List<String> STATUSES = List.of("ANY", "PENDING", "PICKING", "STAGED", "PICKED", "CANCELLED");

    private static final ToolSpec SPEC = new ToolSpec("findFulfillmentTasks",
            "Pick tasks of customer orders (one per delivery batch released), newest first: order number, "
                    + "warehouse, pickStatus (PENDING not started, PICKING under way, STAGED picked into the staging "
                    + "area and waiting to be moved to dispatch, PICKED picking done, CANCELLED the order was "
                    + "cancelled), packStatus, the carton check before shipping (PENDING not started, IN_PROGRESS "
                    + "cartons being checked and labelled, COMPLETED done, CANCELLED), whether someone is assigned and "
                    + "whether it is the user, and when it was assigned, started, picked and checked. Cartons are "
                    + "picked whole by LPN and never repacked. A task stays PICKED after its cartons are checked and "
                    + "shipped, so PICKED and CANCELLED totals count every task ever, not work waiting: what is left to "
                    + "check is the PENDING or IN_PROGRESS packStatus. A short "
                    + "pick does not change pickStatus: the task stays PICKING with the line marked short and its order "
                    + "goes on hold, so this list cannot tell which tasks are short; getOrderPicking shows an order's "
                    + "short lines. Only the tasks the user may see on the pick-list screen, and visibility says which: "
                    + "EVERY_TASK of their warehouses or OWN_TASKS (only tasks assigned to them). With OWN_TASKS, say "
                    + "the list and its count are the user's own tasks, and never read an empty list as an idle "
                    + "warehouse. Filter by pick status; there "
                    + "is no filter by warehouse or pack status, so when a question is about one warehouse or about "
                    + "the carton check, count only from a list that is not truncated, and otherwise say the count cannot be "
                    + "given here. total counts every match; when it is above the tasks returned (truncated), say how "
                    + "many were left out. It does not say who is assigned or anything of the order but its number; "
                    + "getOrderPicking gives the pick lines of one order.",
            ToolSchema.object()
                    .choice("status", "Only tasks in this pick status; ANY for every status", STATUSES)
                    .integer("limit", "How many tasks to return, 1 to " + MAX_ROWS)
                    .build());

    private final FulfillmentService fulfillment;
    private final WarehouseService warehouses;
    private final CurrentUserProvider currentUsers;

    FindFulfillmentTasksTool(FulfillmentService fulfillment, WarehouseService warehouses,
                             CurrentUserProvider currentUsers) {
        this.fulfillment = fulfillment;
        this.warehouses = warehouses;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/fulfillment/tasks";
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
        String status = arguments.choice("status", STATUSES);
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        PageResponse<FulfillmentTask> page = fulfillment.listTasks(user, new FulfillmentTaskQuery(
                "ANY".equals(status) ? null : status, null, null, 0, limit));
        Map<UUID, Optional<WarehouseView>> sites = new HashMap<>();
        List<Row> rows = page.items().stream()
                .map(task -> Row.of(task, sites.computeIfAbsent(task.warehouseId(), warehouses::findWarehouse)
                        .map(WarehouseView::prefix).orElse(null), user))
                .toList();
        return new Answer(Visibility.of(user), rows, page.totalElements(), page.totalElements() > rows.size());
    }

    /**
     * Which tasks fulfillment lets this user see, decided as {@code FulfillmentService.listTasks} decides it: with
     * oversight every task, otherwise the user's own. Always within their warehouses.
     */
    enum Visibility {
        EVERY_TASK, OWN_TASKS;

        static Visibility of(CurrentUser user) {
            return user.can(ToolPermissions.TASK_OVERSIGHT, Action.READ) ? EVERY_TASK : OWN_TASKS;
        }
    }

    /**
     * @param visibility which of the user's warehouses' tasks the list and {@code total} are drawn from
     * @param total      every task that matches among those the user may see
     * @param truncated  {@code total} is above the tasks returned
     */
    record Answer(Visibility visibility, List<Row> tasks, long total, boolean truncated) {
    }

    /**
     * @param assigned       somebody is assigned to the task
     * @param assignedToUser the user asking is that somebody
     */
    record Row(String orderNumber, String warehouseCode, String pickStatus, String packStatus, boolean assigned,
               boolean assignedToUser, Instant assignedAt, Instant startedAt, Instant pickedAt, Instant packedAt) {

        static Row of(FulfillmentTask task, String warehouseCode, CurrentUser user) {
            return new Row(task.orderNumber(), warehouseCode, task.pickStatus(), task.packStatus(),
                    task.assignedUserId() != null, user.userId().equals(task.assignedUserId()), task.assignedAt(),
                    task.startedAt(), task.pickedAt(), task.packedAt());
        }
    }
}

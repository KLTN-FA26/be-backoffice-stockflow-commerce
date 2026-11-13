package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.controller.dto.AssigneeResponse;
import com.stockflow.fulfillment.internal.controller.dto.ConfirmPickRequest;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackingCompletionRequest;
import com.stockflow.fulfillment.internal.controller.dto.PickLineResponse;
import com.stockflow.fulfillment.internal.controller.dto.PutBackRequest;
import com.stockflow.fulfillment.internal.controller.dto.PutBackRowResponse;
import com.stockflow.fulfillment.internal.service.PickLists;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Task-scoped warehouse flow: tasks, picking and packing completion. Designs are not checked here (kltn-docs 08). */
@RestController
@RequestMapping("/api/v1/fulfillment")
@PermissionResource(code = FulfillmentResources.PICK_LISTS, group = "Fulfillment", label = "Pick lists",
        route = "/fulfillment/picks", apiPath = "/api/v1/fulfillment/tasks",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class FulfillmentController {

    private final FulfillmentService fulfillment;
    private final PickLists pickLists;
    private final FulfillmentWebMapper mapper;
    private final com.stockflow.fulfillment.internal.service.ShortageReallocation shortages;

    FulfillmentController(FulfillmentService fulfillment, PickLists pickLists, FulfillmentWebMapper mapper,
                          com.stockflow.fulfillment.internal.service.ShortageReallocation shortages) {
        this.fulfillment = fulfillment;
        this.pickLists = pickLists;
        this.mapper = mapper;
        this.shortages = shortages;
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.CREATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-task", resourceId = "#result?.data()?.taskId()")
    public ApiResponse<FulfillmentTaskResponse> create(@Valid @RequestBody CreateTaskRequest request,
                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.createTask(request.orderId(), request.assignedUserId(),
                request.destination(), request.stagingLocationCode(), actor)));
    }

    @GetMapping("/tasks")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<PageResponse<FulfillmentTaskResponse>> list(@AuthenticatedUser CurrentUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID assignedUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(fulfillment
                .listTasks(actor, new FulfillmentTaskQuery(status, warehouseId, assignedUserId, page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size))
                .map(mapper::toResponse));
    }

    /**
     * Who a task of one warehouse can go to: the active warehouse staff and managers assigned to it.
     * The warehouse is required, and a manager is limited to their own. The answer is deliberately not
     * paged: it is bounded by one warehouse's staff, and the screen needs all of them to pick from.
     */
    @GetMapping("/assignees")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    public ApiResponse<List<AssigneeResponse>> assignees(@RequestParam UUID warehouseId,
                                                         @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(fulfillment.assignees(warehouseId, actor).stream().map(mapper::toResponse).toList());
    }

    @PutMapping("/tasks/{taskId}/assignment")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> assign(@PathVariable UUID taskId,
            @Valid @RequestBody AssignmentRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.assign(taskId, request.assignedUserId(), actor)));
    }

    /**
     * Where a PENDING task takes its cartons (kltn-docs 07 §4.1 step 3, BR-08): {@code DISPATCH}, or {@code STAGING}
     * into {@code stagingLocationCode} (optional when the warehouse has one active staging area). Past PENDING, 409
     * {@code PICK_TASK_STATE_CONFLICT}; a staging area that is not one of the warehouse's, 422 {@code STAGING_AREA_INVALID}.
     */
    @PutMapping("/tasks/{taskId}/destination")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> changeDestination(@PathVariable UUID taskId,
            @Valid @RequestBody DestinationRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.changeDestination(taskId, request.destination(),
                request.stagingLocationCode(), actor)));
    }

    /**
     * Moves a STAGED task to dispatch, all of it (kltn-docs 07 §4.3 step 8, BR-11): every staged carton is deducted
     * where it stands and the task is PICKED, ready for checking and labelling. A decision of whoever assigns the work
     * (the warehouse manager). Not STAGED, 409 {@code PICK_TASK_STATE_CONFLICT}; the order on hold, 409
     * {@code PICK_ORDER_ON_HOLD}. Again, 200 with the task as it is.
     */
    @PostMapping("/tasks/{taskId}/dispatch-transfer")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> moveToDispatch(@PathVariable UUID taskId,
                                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.moveToDispatch(taskId, actor)));
    }

    /**
     * A top-up (kltn-docs 07 §4.3 step 7, BR-07): cartons the order holds that no line of the task takes yet become new
     * lines of the same task, keeping its destination; a STAGED or PICKED task goes back to PICKING. The answer is the
     * new lines - none when there is nothing new.
     */
    @PostMapping("/tasks/{taskId}/top-up")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<List<PickLineResponse>> topUp(@PathVariable UUID taskId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.topUp(taskId, actor).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/tasks/{taskId}")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<FulfillmentTaskResponse> find(@PathVariable UUID taskId,
                                             @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.findTask(taskId, actor)));
    }

    /**
     * Starts picking, and plans the task's lines if it has none (a task opened before pick lines existed).
     * Starting a task already PICKING answers with it unchanged.
     */
    @PostMapping("/tasks/{taskId}/picking-start")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> startPicking(@PathVariable UUID taskId,
                                                     @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.startPicking(taskId, actor)));
    }

    /**
     * Closes picking once every line is PICKED - or STAGED, for a STAGING task, which then waits STAGED for the move to
     * dispatch: {@code 409 PICK_LINES_INCOMPLETE} otherwise (docs 07 step 6).
     */
    @PostMapping("/tasks/{taskId}/picking-completion")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> completePicking(@PathVariable UUID taskId,
                                                        @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePicking(taskId, actor)));
    }

    /**
     * The pick list: one line per hold of the order's stock lines, in walking order (print lines have
     * none, docs 07 BR-06). The assignee, and managers of the task's warehouse.
     */
    @GetMapping("/tasks/{taskId}/pick-lines")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<PageResponse<PickLineResponse>> pickLines(@PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.lines(taskId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, actor)
                .map(mapper::toResponse));
    }

    /**
     * Confirms one line by scan (kltn-docs 07 §4.2 step 5): the bin, then the carton's LPN - only the line's own; a SKU,
     * a barcode or another carton is 409 {@code PICK_ITEM_MISMATCH} (BR-10). No quantity: the carton is taken whole
     * (BR-04). In the same transaction ({@code inventory :: consumption}, SCRUM-275): a DISPATCH task deducts the line's
     * hold (PICKED); a STAGING task moves the carton to its staging area, still held, nothing deducted (STAGED). Only
     * the assignee; the same scan again answers 200 with the line unchanged. Send an {@code Idempotency-Key} per scan.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/confirmation")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> confirmPick(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody ConfirmPickRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.confirm(taskId, lineId, request.locationCode(),
                request.lpn(), actor)));
    }

    /**
     * Goods picked for orders cancelled afterwards, still off their bins (docs 07 BR-05, 17 §4.5 step 15):
     * the put-back screen's queue. Limited to the caller's warehouses; {@code warehouseId} narrows it to one.
     */
    @GetMapping("/put-backs")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.READ)
    public ApiResponse<PageResponse<PutBackRowResponse>> putBacks(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.putBacks(warehouseId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                actor).map(mapper::toResponse));
    }

    /**
     * Puts the carton one line picked back onto an active bin of the task's warehouse, whole, as AVAILABLE stock,
     * confirmed by scanning its LPN (kltn-docs 07 §4.5 step 14): the carton is IN_STOCK there again, sealed, with its
     * LPN and count. Any warehouse staff member or manager of that warehouse, not only the picker. The same line and
     * LPN again answers 200 with the line unchanged.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/put-back")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> putBack(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody PutBackRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.putBack(taskId, lineId, request.locationCode(),
                request.lpn(), actor)));
    }

    /**
     * X-SHORT (kltn-docs 07 §4.4 step 9, BR-09): the assignee is at the line's bin and cannot take its carton -
     * {@code NOT_FOUND} or {@code DAMAGED} - a picking step, so the picking permission. The line turns SHORT of the
     * whole carton, nothing is deducted, the order goes ON_HOLD ({@code INVENTORY}) for the warehouse manager and the carton
     * is counted blind. The same report again answers 200 with the line unchanged.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/short")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> reportShort(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody ShortPickRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.reportShort(taskId, lineId, request.locationCode(),
                request.reason(), actor)));
    }

    /**
     * Resolves a SHORT line by reallocation (07 §4.4 step 10): as many whole cartons are allocated from other pickable
     * bins of the warehouse, FIFO, never the short one; the answer is the new lines to pick. The order resumes
     * once no line is SHORT. Too little stock is 409 {@code INSUFFICIENT_STOCK} and nothing changes. A decision of
     * whoever assigns the work (the warehouse manager), not of the picker.
     *
     * <p>The same for a line whose carton the check station removed as damaged (08 §4.2 step 4, handled as a short
     * pick): a replacement carton is allocated and joins the task as a top-up (07 §4.3 step 7), to be picked, checked
     * in the same session and handed over. Too little stock is 409 {@code INSUFFICIENT_STOCK}, and the order is then
     * held {@code INVENTORY} for Sales to decide (07 §4.4 step 11).</p>
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/reallocation")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<List<PickLineResponse>> reallocateShort(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(shortages.reallocate(taskId, lineId, actor).stream().map(mapper::toResponse)
                .toList());
    }

    /**
     * Completes packing (G4-5, docs 08 step 7-9): every sealed package not shipped yet becomes one shipment,
     * READY_TO_DISPATCH ({@code shipmentId} in the answer). Without a body, everything the order wants must be packed
     * (409 PACKING_INCOMPLETE); {@code partial} ships what is sealed and leaves the rest for later. Completed again,
     * the same.
     */
    @PostMapping("/tasks/{taskId}/packing-completion")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> completePacking(@PathVariable UUID taskId,
            @Valid @RequestBody(required = false) PackingCompletionRequest request,
            @AuthenticatedUser CurrentUser actor) {
        PackingCompletion completion = request == null ? PackingCompletion.whole()
                : new PackingCompletion(request.requestId(), request.partial(), request.note());
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePacking(taskId, completion, actor)));
    }

    /**
     * {@code destination} defaults to {@code DISPATCH}; {@code stagingLocationCode} names the staging area of a
     * {@code STAGING} task, optional when the warehouse has exactly one active staging area.
     */
    record CreateTaskRequest(@NotNull UUID orderId, @NotNull UUID assignedUserId,
                             com.stockflow.fulfillment.api.PickDestination destination,
                             @Size(max = 64) String stagingLocationCode) { }
    /** As {@link CreateTaskRequest}: the staging area is ignored for a {@code DISPATCH} task. */
    record DestinationRequest(@NotNull com.stockflow.fulfillment.api.PickDestination destination,
                              @Size(max = 64) String stagingLocationCode) { }
    /** The bin, as scanned, and why its carton cannot be taken. A carton is short whole: no quantity is entered. */
    record ShortPickRequest(@NotBlank @Size(max = 64) String locationCode,
                            @NotNull com.stockflow.fulfillment.internal.domain.ShortPickReason reason) { }
    record AssignmentRequest(@NotNull UUID assignedUserId) { }
}

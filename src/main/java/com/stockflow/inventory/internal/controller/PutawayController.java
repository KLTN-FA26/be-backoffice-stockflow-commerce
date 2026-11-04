package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.PutawayConfirmationRequest;
import com.stockflow.inventory.internal.controller.dto.PutawaySuggestionResponse;
import com.stockflow.inventory.internal.controller.dto.PutawayTaskResponse;
import com.stockflow.inventory.internal.service.PutawayService;
import com.stockflow.inventory.internal.service.PutawayService.TaskView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Putaway tasks (SCRUM-92): what is waiting to go from the receiving area into bins, and where it could go. The
 * resource is the warehouse's {@code warehouse-putaway-tasks}; the tasks are served here because what is left and
 * where the goods stand are facts of the stock.
 */
@RestController
@Tag(name = "Putaway tasks", description = "Received goods waiting to be put into bins, with ranked bin suggestions")
@RequestMapping("/api/v1/putaway-tasks")
@PermissionResource(
        code = InventoryResources.PUTAWAY_TASKS,
        group = "Warehouse",
        label = "Putaway tasks",
        route = "/warehouse/putaway-tasks",
        apiPath = "/api/v1/putaway-tasks",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class PutawayController {

    private final PutawayService putaway;

    PutawayController(PutawayService putaway) {
        this.putaway = putaway;
    }

    @GetMapping
    @Operation(summary = "Putaway tasks of a receipt, or the open ones of a warehouse",
            description = "Give receiptId (every task of the receipt) or warehouseId (the PENDING and IN_PROGRESS "
                    + "tasks whose goods stand in it); neither is 400 VALIDATION_FAILED. No suggestions here.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TASKS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<PutawayTaskResponse>> list(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID receiptId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(putaway.list(warehouseId, receiptId, status, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(PutawayController::toResponse));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "One putaway task: what is left, where it stands and up to 10 ranked bins")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TASKS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTaskResponse> get(@PathVariable UUID taskId) {
        return ApiResponse.ok(toResponse(putaway.get(taskId)));
    }

    @PostMapping("/{taskId}/confirmations")
    @Operation(summary = "Put a task's goods into a bin, by scan",
            description = "A carton task takes {locationCode, lpn}, a loose one {locationCode, quantity}; the other "
                    + "field is 400. A loose task may be confirmed in several parts. Answers the task as it now "
                    + "stands. Refused: DONE task 409, other carton 409, more than left 422, a bin that is not a bin "
                    + "422, another warehouse's, unusable, of the wrong class or full 409.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TASKS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTaskResponse> confirm(@PathVariable UUID taskId,
                                                    @Valid @RequestBody PutawayConfirmationRequest request,
                                                    @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(putaway.confirm(taskId, request.locationCode(), request.lpn(),
                request.quantity(), actor.userId())));
    }

    private static PutawayTaskResponse toResponse(TaskView task) {
        return new PutawayTaskResponse(task.id(), task.goodsReceiptId(), task.goodsReceiptLineId(), task.sku(),
                task.lotNumber(), task.quantity(), task.left(), task.sourceLocationCode(), task.lpn(), task.status(),
                task.targetLocationCode(), task.suggestions().stream()
                .map(s -> new PutawaySuggestionResponse(s.locationCode(), s.reason().name(), s.freeUnits(),
                        s.freeWeightKg())).toList());
    }
}

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
import com.stockflow.inventory.internal.controller.dto.AddCountLineRequest;
import com.stockflow.inventory.internal.controller.dto.AssignCycleCountRequest;
import com.stockflow.inventory.internal.controller.dto.CountLineRequest;
import com.stockflow.inventory.internal.controller.dto.CycleCountAssigneeResponse;
import com.stockflow.inventory.internal.controller.dto.CycleCountResponse;
import com.stockflow.inventory.internal.controller.dto.CycleCountSummaryResponse;
import com.stockflow.inventory.internal.controller.dto.PlanCycleCountRequest;
import com.stockflow.inventory.internal.controller.dto.PlannedCycleCountResponse;
import com.stockflow.inventory.internal.controller.dto.RecountRequest;
import com.stockflow.inventory.internal.service.CycleCountSummary;
import com.stockflow.inventory.internal.service.CycleCountView;
import com.stockflow.inventory.internal.service.CycleCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

import java.util.List;
import java.util.UUID;

/**
 * Cycle counts (WBS 3.6.6, SCRUM-146): planned by a manager or planner ({@code CREATE}), counted on the shelf and
 * submitted ({@code UPDATE}), sent for a recount or called off by whoever decides counts ({@code APPROVE}, the
 * warehouse manager by the seeded grants). Every endpoint is warehouse-scoped (BR-SEC-002).
 *
 * <p>The blind rule is applied in the service, from {@code mayDecide}: a caller without {@code APPROVE} never sees a
 * blind count's expected quantities, note or adjustments, before or after submission. Posting the variance is not
 * here: submission raises {@code COUNT_VARIANCE} adjustments, decided on the adjustment screen (SCRUM-145).</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/cycle-counts")
@Tag(name = "Cycle counts", description = "Plan a count, count it blind, submit the variances for approval, recount")
@PermissionResource(
        code = InventoryResources.CYCLE_COUNTS,
        group = "Inventory",
        label = "Cycle counts",
        route = "/inventory/cycle-counts",
        apiPath = "/api/v1/inventory/cycle-counts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class CycleCountController {

    private final CycleCounts counts;

    CycleCountController(CycleCounts counts) {
        this.counts = counts;
    }

    @GetMapping
    @Operation(summary = "Cycle counts, newest first, without their lines; by status and warehouse")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<CycleCountSummaryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(counts.list(status, warehouseId, decides(actor), page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(CycleCountController::toSummary));
    }

    @GetMapping("/{countId}")
    @Operation(summary = "One cycle count with its lines; blind for a counter")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> get(@PathVariable UUID countId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.get(countId, decides(actor))));
    }

    /** {@code CREATE}: choosing who counts is part of planning, so who plans picks from this list. */
    @GetMapping("/assignees")
    @Operation(summary = "Who a count of this warehouse may be handed to")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.CREATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<List<CycleCountAssigneeResponse>> assignees(@RequestParam UUID warehouseId) {
        return ApiResponse.ok(counts.assignees(warehouseId).stream()
                .map(user -> new CycleCountAssigneeResponse(user.userId(), user.displayName(), user.roles()))
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Plan a count over zones, shelves, bins and SKUs; slots on another count are skipped")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.CREATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PlannedCycleCountResponse> plan(@Valid @RequestBody PlanCycleCountRequest request,
                                                       @AuthenticatedUser CurrentUser actor) {
        PlanCycleCountRequest.Scope scope = request.scope();
        CycleCounts.PlannedCount planned = counts.plan(new CycleCounts.PlanCycleCountCommand(request.warehouseId(),
                request.plannedDate(), request.assignedUserId(), request.blindOrDefault(), request.note(),
                scope.zoneIds(), scope.shelfCodes(), scope.locationCodes(), scope.skus()), decides(actor));
        return ApiResponse.ok(new PlannedCycleCountResponse(toResponse(planned.count()), planned.skipped().stream()
                .map(slot -> new PlannedCycleCountResponse.Skipped(slot.locationCode(), slot.sku(), slot.lotNumber(),
                        slot.lpn(), slot.countNumber()))
                .toList()));
    }

    @PutMapping("/{countId}/assignment")
    @Operation(summary = "Hand a planned count to a warehouse staff member or manager")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.CREATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> assign(@PathVariable UUID countId,
                                                  @Valid @RequestBody AssignCycleCountRequest request,
                                                  @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.assign(countId, request.assignedUserId(), decides(actor))));
    }

    @PutMapping("/{countId}/lines/{lineId}/count")
    @Operation(summary = "Record what was found on a line; the book quantity is re-read now")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> count(@PathVariable UUID countId, @PathVariable UUID lineId,
                                                 @Valid @RequestBody CountLineRequest request,
                                                 @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.count(countId, lineId, request.countedQty(), request.present(),
                actor.userId(), decides(actor))));
    }

    @PostMapping("/{countId}/lines")
    @Operation(summary = "Add a slot found on the shelf: the bin it was found in, with a carton's LPN or a SKU and lot")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> addLine(@PathVariable UUID countId,
                                                   @Valid @RequestBody AddCountLineRequest request,
                                                   @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.addLine(countId, new CycleCounts.AddCountLineCommand(
                request.locationCode(), request.sku(), request.lotNumber(), request.lpn(), request.countedQty(),
                actor.userId(), decides(actor)))));
    }

    @PostMapping("/{countId}/submission")
    @Operation(summary = "Submit a fully counted count; each variance becomes a pending adjustment")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> submit(@PathVariable UUID countId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.submit(countId, actor.userId(), decides(actor))));
    }

    @PostMapping("/{countId}/recounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Recount lines whose variance was rejected; returns the new blind count")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.APPROVE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> recount(@PathVariable UUID countId,
                                                   @Valid @RequestBody RecountRequest request,
                                                   @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.recount(countId, request.lineIds(), request.note(), actor.userId())));
    }

    @Schema(name = "CancelCycleCountRequest")
    record CancelRequest(@NotBlank(message = "reason is required") @Size(max = 500) String reason) { }

    @PostMapping("/{countId}/cancellation")
    @Operation(summary = "Call a count off; a SHORT_PICK recount's bin, SKU and lot are sellable again")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.APPROVE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> cancel(@PathVariable UUID countId, @Valid @RequestBody CancelRequest request,
                                                  @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.cancel(countId, request.reason(), actor.userId().toString())));
    }

    /** Who decides counts (APPROVE: the warehouse manager by the seeded grants) sees the books; a counter does not. */
    private static boolean decides(CurrentUser actor) {
        return actor.can(InventoryResources.CYCLE_COUNTS, Action.APPROVE);
    }

    private static CycleCountSummaryResponse toSummary(CycleCountSummary count) {
        return new CycleCountSummaryResponse(count.id(), count.number(), count.warehouseId(), count.triggerType(),
                count.status(), count.blind(), count.assignedUserId(), count.plannedDate(), count.startedAt(),
                count.completedAt(), count.note(), count.createdAt(), count.lineCount(), count.countedCount(),
                count.varianceCount());
    }

    private static CycleCountResponse toResponse(CycleCountView count) {
        return new CycleCountResponse(count.id(), count.number(), count.warehouseId(), count.triggerType(),
                count.status(), count.blind(), count.assignedUserId(), count.plannedDate(), count.startedAt(),
                count.completedAt(), count.note(), count.createdAt(),
                count.lines().stream().map(line -> new CycleCountResponse.Line(line.id(), line.locationCode(),
                        line.sku(), line.lotNumber(), line.stockPackageId(), line.lpn(), line.expectedQty(),
                        line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired(),
                        line.adjustments().stream().map(adjustment -> new CycleCountResponse.Adjustment(
                                adjustment.id(), adjustment.number(), adjustment.quantityDelta(), adjustment.status()))
                                .toList())).toList());
    }
}

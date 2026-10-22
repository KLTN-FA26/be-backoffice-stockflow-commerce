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
import com.stockflow.inventory.internal.domain.Recounts;
import com.stockflow.inventory.internal.service.CycleCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cycle counts (WBS 3.10.5), so far the blind recounts a short pick opens (docs 04c X-SHORT step 03): listed, and
 * called off by the warehouse manager. Counting and posting the difference belong to the cycle-count story. A counter
 * sees what to count, not what the books say; whoever may approve counts
 * ({@code inventory-cycle-counts:APPROVE}) sees both.
 */
@RestController
@RequestMapping("/api/v1/inventory/cycle-counts")
@Tag(name = "Cycle counts", description = "Recounts opened by short picks")
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

    @Schema(name = "CycleCount")
    record CycleCountResponse(UUID id, String countNumber, UUID warehouseId, String triggerType, String status,
                              boolean blind, LocalDate plannedDate,
                              @Schema(description = "Null on a blind count for a counter") String note,
                              Instant createdAt,
                              List<LineResponse> lines) { }

    @Schema(name = "CycleCountLine")
    record LineResponse(UUID id, String locationCode, String sku, String lotNumber,
                        @Schema(description = "What the books say; null on a blind count for a counter")
                        Integer expectedQty,
                        Integer countedQty) { }

    @GetMapping
    @Operation(summary = "Cycle counts, newest first; by status and warehouse")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<CycleCountResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        // Who decides counts (APPROVE: the warehouse manager by the seeded grants) sees the books; a counter does not.
        boolean decides = actor.can(InventoryResources.CYCLE_COUNTS, Action.APPROVE);
        return ApiResponse.ok(counts.list(status, warehouseId, decides, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(CycleCountController::toResponse));
    }

    @Schema(name = "CancelCycleCountRequest")
    record CancelRequest(@NotBlank(message = "reason is required") @Size(max = 500) String reason) { }

    @PostMapping("/{countId}/cancellation")
    @Operation(summary = "Call a recount off; a SHORT_PICK recount's bin, SKU and lot are sellable again")
    @RequiresPermission(resource = InventoryResources.CYCLE_COUNTS, action = Action.APPROVE, scope = DataScope.WAREHOUSE)
    public ApiResponse<CycleCountResponse> cancel(@PathVariable UUID countId, @Valid @RequestBody CancelRequest request,
                                                  @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(counts.cancel(countId, request.reason(), actor.userId().toString())));
    }

    private static CycleCountResponse toResponse(Recounts.Recount count) {
        return new CycleCountResponse(count.id(), count.number(), count.warehouseId(), count.triggerType(),
                count.status(), count.blind(), count.plannedDate(), count.note(), count.createdAt(),
                count.lines().stream().map(line -> new LineResponse(line.id(), line.locationCode(), line.sku(),
                        line.lotNumber(), line.expectedQty(), line.countedQty())).toList());
    }
}

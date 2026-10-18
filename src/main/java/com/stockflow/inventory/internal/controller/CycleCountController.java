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
import com.stockflow.common.security.Role;
import com.stockflow.inventory.internal.domain.Recounts;
import com.stockflow.inventory.internal.service.CycleCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cycle counts (WBS 3.10.5), so far the blind recounts a short pick opens (docs 04c X-SHORT step 03). Read only:
 * counting and posting the difference belong to the cycle-count story. A counter sees what to count, not what the
 * books say; a warehouse manager sees both.
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
                              boolean blind, LocalDate plannedDate, String note, Instant createdAt,
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
        boolean manager = actor != null && actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.SYSTEM_ADMIN);
        return ApiResponse.ok(counts.list(status, warehouseId, manager, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(CycleCountController::toResponse));
    }

    private static CycleCountResponse toResponse(Recounts.Recount count) {
        return new CycleCountResponse(count.id(), count.number(), count.warehouseId(), count.triggerType(),
                count.status(), count.blind(), count.plannedDate(), count.note(), count.createdAt(),
                count.lines().stream().map(line -> new LineResponse(line.id(), line.locationCode(), line.sku(),
                        line.lotNumber(), line.expectedQty(), line.countedQty())).toList());
    }
}

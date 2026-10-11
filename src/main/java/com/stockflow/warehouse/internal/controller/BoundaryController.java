package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.BoundaryRequests;
import com.stockflow.warehouse.internal.controller.dto.BoundaryResponse;
import com.stockflow.warehouse.internal.service.BoundaryLayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Walls and doors of a warehouse map. Guarded by {@link WarehouseResources#LOCATIONS}, declared on
 * {@link WarehouseLocationResourceDeclaration}. Boundaries are read through the layout (#25).
 *
 * <p>An end off the map answers {@code 409 LAYOUT_OUT_OF_BOUNDS} (BR-06). A boundary is never
 * checked against shelves or areas: a wall runs beside them.</p>
 */
@RestController
@Tag(name = "Warehouse map: boundaries", description = "Walls and doors. Read them through the layout")
@RequestMapping("/api/v1")
class BoundaryController {

    private final BoundaryLayoutService boundaries;
    private final BoundaryWebMapper mapper;

    BoundaryController(BoundaryLayoutService boundaries, BoundaryWebMapper mapper) {
        this.boundaries = boundaries;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/boundaries")
    @Operation(summary = "Draw a wall or a door",
            description = "A wall: passable false and no operationalStatus. A door: an operationalStatus. "
                + "Both ends inside the map (409 LAYOUT_OUT_OF_BOUNDS).")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<BoundaryResponse> create(@PathVariable UUID warehouseId,
                                                @Valid @RequestBody BoundaryRequests.CreateBoundary request) {
        return ApiResponse.ok(mapper.toResponse(
                boundaries.createBoundary(mapper.toCommand(warehouseId, request))));
    }

    @PutMapping("/boundaries/{boundaryId}")
    @Operation(summary = "Redraw a boundary",
            description = "Send the version from the last read; a stale one answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<BoundaryResponse> update(@PathVariable UUID boundaryId,
                                                @Valid @RequestBody BoundaryRequests.UpdateBoundary request) {
        return ApiResponse.ok(mapper.toResponse(
                boundaries.updateBoundary(mapper.toCommand(boundaryId, request))));
    }

    /**
     * A hard delete (issue #18 D11): a boundary has no status and nothing points at it. Carries the
     * {@code version} of the last read, like an update - a delete cannot be undone, so one based on
     * a stale view answers {@code 409 OPTIMISTIC_LOCK} instead.
     */
    @DeleteMapping("/boundaries/{boundaryId}")
    @Operation(summary = "Delete a boundary for good",
            description = "A hard delete, so it names the version it removes: version is required (400 "
                + "without it), and a stale one answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable UUID boundaryId,
                                    @Parameter(description = "The version from the last read")
                                    @RequestParam long version) {
        boundaries.deleteBoundary(boundaryId, version);
        return ApiResponse.ok();
    }
}

package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.AreaRequests;
import com.stockflow.warehouse.internal.controller.dto.AreaResponse;
import com.stockflow.warehouse.internal.service.AreaLayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Floor areas of a warehouse map. Guarded by {@link WarehouseResources#LOCATIONS}, declared on
 * {@link WarehouseLocationResourceDeclaration}. Areas are read through the layout (#25).
 *
 * <p>Placing, moving or resizing an area answers {@code 409 LAYOUT_OUT_OF_BOUNDS} (BR-06) or
 * {@code 409 LAYOUT_OVERLAP} (BR-07); a code already used in the warehouse
 * {@code 409 AREA_CODE_ALREADY_EXISTS}; a storage area made {@code NON_STORAGE}
 * {@code 409 AREA_TYPE_CHANGE_NOT_ALLOWED} (issue #18 D10).</p>
 */
@RestController
@Tag(name = "Warehouse map: areas", description = "Floor areas: receiving, QC, packing, dispatch, "
        + "overflow, non-storage. Read them through the layout")
@RequestMapping("/api/v1")
class AreaController {

    private final AreaLayoutService areas;
    private final AreaWebMapper mapper;

    AreaController(AreaLayoutService areas, AreaWebMapper mapper) {
        this.areas = areas;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/areas")
    @Operation(summary = "Place an area on the map",
            description = "Every type but NON_STORAGE needs location and gets a storage location coded "
                + "prefix-area (HCM-RCV02); for NON_STORAGE the location fields are absent from the "
                + "response. Off the map: 409 LAYOUT_OUT_OF_BOUNDS (BR-06). Overlapping a shelf or "
                + "area that is not INACTIVE: 409 LAYOUT_OVERLAP (BR-07). A code in use: 409 "
                + "AREA_CODE_ALREADY_EXISTS.")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<AreaResponse> create(@PathVariable UUID warehouseId,
                                            @Valid @RequestBody AreaRequests.CreateArea request) {
        return ApiResponse.ok(mapper.toResponse(areas.createArea(mapper.toCommand(warehouseId, request))));
    }

    @PutMapping("/areas/{areaId}")
    @Operation(summary = "Move, resize or retype an area",
            description = "A full replacement: location.storageClass left out goes back to NORMAL. "
                + "NON_STORAGE to a storage type creates the location; a storage type to "
                + "NON_STORAGE is 409 AREA_TYPE_CHANGE_NOT_ALLOWED. Off the map: 409 "
                + "LAYOUT_OUT_OF_BOUNDS (BR-06). Overlapping a shelf or area that is not INACTIVE: "
                + "409 LAYOUT_OVERLAP (BR-07). Send the version from the last read; a stale one "
                + "answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<AreaResponse> update(@PathVariable UUID areaId,
                                            @Valid @RequestBody AreaRequests.UpdateArea request) {
        return ApiResponse.ok(mapper.toResponse(areas.updateArea(mapper.toCommand(areaId, request))));
    }

    @PutMapping("/areas/{areaId}/status")
    @Operation(summary = "Change an area's status",
            description = "Written to the area and its storage location alike. Leaving INACTIVE checks the "
                + "place is still free.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<AreaResponse> changeStatus(@PathVariable UUID areaId,
                                                  @Valid @RequestBody AreaRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(areas.changeAreaStatus(areaId, request.status())));
    }
}

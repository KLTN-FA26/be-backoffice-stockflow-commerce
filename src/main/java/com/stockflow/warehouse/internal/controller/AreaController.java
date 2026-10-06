package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.AreaRequests;
import com.stockflow.warehouse.internal.controller.dto.AreaResponse;
import com.stockflow.warehouse.internal.service.AreaLayoutService;
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
@RequestMapping("/api/v1")
class AreaController {

    private final AreaLayoutService areas;
    private final AreaWebMapper mapper;

    AreaController(AreaLayoutService areas, AreaWebMapper mapper) {
        this.areas = areas;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<AreaResponse> create(@PathVariable UUID warehouseId,
                                            @Valid @RequestBody AreaRequests.CreateArea request) {
        return ApiResponse.ok(mapper.toResponse(areas.createArea(mapper.toCommand(warehouseId, request))));
    }

    @PutMapping("/areas/{areaId}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<AreaResponse> update(@PathVariable UUID areaId,
                                            @Valid @RequestBody AreaRequests.UpdateArea request) {
        return ApiResponse.ok(mapper.toResponse(areas.updateArea(mapper.toCommand(areaId, request))));
    }

    @PutMapping("/areas/{areaId}/status")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<AreaResponse> changeStatus(@PathVariable UUID areaId,
                                                  @Valid @RequestBody AreaRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(areas.changeAreaStatus(areaId, request.status())));
    }
}

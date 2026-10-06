package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.internal.controller.dto.StorageLocationResponse;
import com.stockflow.warehouse.internal.controller.dto.WarehouseLayoutResponse;
import com.stockflow.warehouse.internal.service.WarehouseLayoutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reading the map: the whole layout of a warehouse, and one storage location by the code on its
 * label. Guarded by {@link WarehouseResources#LOCATIONS}, declared on
 * {@link WarehouseLocationResourceDeclaration}.
 */
@RestController
@RequestMapping("/api/v1")
class LayoutController {

    private final WarehouseLayoutService layout;
    private final WarehouseService locations;
    private final LayoutWebMapper mapper;

    LayoutController(WarehouseLayoutService layout, WarehouseService locations, LayoutWebMapper mapper) {
        this.layout = layout;
        this.locations = locations;
        this.mapper = mapper;
    }

    /** Includes {@code INACTIVE} places; see {@link WarehouseLayoutResponse}. */
    @GetMapping("/warehouses/{warehouseId}/layout")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ApiResponse<WarehouseLayoutResponse> layout(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(mapper.toResponse(layout.layoutOf(warehouseId)));
    }

    /**
     * Case does not matter: {@code hn-a01-2-03} finds {@code HN-A01-2-03}. The code is not echoed in
     * the error: it is whatever the client put in the path, and would go into the log line as is.
     */
    @GetMapping("/locations/{locationCode}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ApiResponse<StorageLocationResponse> location(@PathVariable String locationCode) {
        return ApiResponse.ok(locations.findLocation(locationCode)
                .map(mapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                        "No storage location has this code")));
    }
}

package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.RegisterWarehouseRequest;
import com.stockflow.warehouse.internal.controller.dto.UpdateWarehouseRequest;
import com.stockflow.warehouse.internal.controller.dto.WarehouseResponse;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.service.ListWarehousesQuery;
import com.stockflow.warehouse.internal.service.WarehouseLayoutService;
import jakarta.validation.Valid;
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

import java.util.UUID;

/**
 * Warehouses and their map frame. Every handler is {@code public}: a non-public handler may not be
 * advised, and {@code @RequiresPermission} would silently not apply.
 */
@RestController
@RequestMapping("/api/v1/warehouses")
@PermissionResource(code = WarehouseResources.WAREHOUSES, group = "Warehouse", label = "Warehouses",
        route = "/admin/warehouses", apiPath = "/api/v1/warehouses",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class WarehouseController {

    private final WarehouseLayoutService layout;
    private final WarehouseWebMapper mapper;

    WarehouseController(WarehouseLayoutService layout, WarehouseWebMapper mapper) {
        this.layout = layout;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.CREATE)
    public ApiResponse<WarehouseResponse> register(@Valid @RequestBody RegisterWarehouseRequest request) {
        return ApiResponse.ok(mapper.toResponse(layout.register(mapper.toCommand(request))));
    }

    @GetMapping
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.READ)
    public ApiResponse<PageResponse<WarehouseResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) WarehouseStatus status,
            @RequestParam(required = false) String sort) {
        var query = new ListWarehousesQuery(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                search, status, sort);
        return ApiResponse.ok(layout.list(query).map(mapper::toResponse));
    }

    @GetMapping("/{warehouseId}")
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.READ)
    public ApiResponse<WarehouseResponse> get(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(mapper.toResponse(layout.get(warehouseId)));
    }

    /** Shrinking the map below what is on it is a {@code 409 LAYOUT_OUT_OF_BOUNDS} (BR-06). */
    @PutMapping("/{warehouseId}")
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.UPDATE)
    public ApiResponse<WarehouseResponse> update(@PathVariable UUID warehouseId,
                                                 @Valid @RequestBody UpdateWarehouseRequest request) {
        return ApiResponse.ok(mapper.toResponse(layout.update(mapper.toCommand(warehouseId, request))));
    }

    @PostMapping("/{warehouseId}/activation")
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.UPDATE)
    public ApiResponse<WarehouseResponse> activate(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(mapper.toResponse(layout.activate(warehouseId)));
    }

    /** Does not cascade to locations; they read as unusable while the warehouse is inactive. */
    @PostMapping("/{warehouseId}/deactivation")
    @RequiresPermission(resource = WarehouseResources.WAREHOUSES, action = Action.UPDATE)
    public ApiResponse<WarehouseResponse> deactivate(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(mapper.toResponse(layout.deactivate(warehouseId)));
    }
}

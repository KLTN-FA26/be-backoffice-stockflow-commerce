package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.CreateZoneRequest;
import com.stockflow.warehouse.internal.controller.dto.UpdateZoneRequest;
import com.stockflow.warehouse.internal.controller.dto.ZoneResponse;
import com.stockflow.warehouse.internal.service.WarehouseLayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Zones of a warehouse map. Guarded by {@link WarehouseResources#LOCATIONS}, which is declared on
 * {@link WarehouseLocationResourceDeclaration} because the map's endpoints span several controllers.
 */
@RestController
@Tag(name = "Warehouse map: zones", description = "Optional colour groups of shelves on a map")
@RequestMapping("/api/v1")
class ZoneController {

    private final WarehouseLayoutService layout;
    private final WarehouseWebMapper mapper;

    ZoneController(WarehouseLayoutService layout, WarehouseWebMapper mapper) {
        this.layout = layout;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/zones")
    @Operation(summary = "Create a zone",
            description = "A name already used in the warehouse answers 409 ZONE_NAME_ALREADY_EXISTS.")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ZoneResponse> create(@PathVariable UUID warehouseId,
                                            @Valid @RequestBody CreateZoneRequest request) {
        return ApiResponse.ok(mapper.toResponse(layout.createZone(mapper.toCommand(warehouseId, request))));
    }

    @GetMapping("/warehouses/{warehouseId}/zones")
    @Operation(summary = "List the zones of a warehouse, by name")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ApiResponse<List<ZoneResponse>> list(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(mapper.toZoneResponses(layout.listZones(warehouseId)));
    }

    @PutMapping("/zones/{zoneId}")
    @Operation(summary = "Rename or recolour a zone",
            description = "Send the version from the last read; a stale one answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ZoneResponse> update(@PathVariable UUID zoneId,
                                            @Valid @RequestBody UpdateZoneRequest request) {
        return ApiResponse.ok(mapper.toResponse(layout.updateZone(mapper.toCommand(zoneId, request))));
    }
}

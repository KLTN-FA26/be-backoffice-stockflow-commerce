package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeRequest;
import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeResponse;
import com.stockflow.fulfillment.internal.controller.dto.VehicleTypeUpdateRequest;
import com.stockflow.fulfillment.internal.service.VehicleTypes;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The vehicle-type catalogue of full-truckload load planning (SCRUM-287; kltn-docs 09 §3, §4.2).
 * {@code fulfillment-vehicle-types:READ} to list and read (dispatch staff and coordinators choose from it);
 * {@code :UPDATE} to keep it - seeded for the warehouse manager. A type is retired with PUT status INACTIVE; there is
 * no delete, so a preset (20DC, 40DC, 40HC, 45HC) cannot be deleted.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/vehicle-types")
@Tag(name = "Fulfillment - load planning")
class VehicleTypeController {

    private final VehicleTypes vehicleTypes;
    private final VehicleTypeWebMapper mapper;

    VehicleTypeController(VehicleTypes vehicleTypes, VehicleTypeWebMapper mapper) {
        this.vehicleTypes = vehicleTypes;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Vehicle types, by name (paged); status ACTIVE / INACTIVE, search in code or name")
    @RequiresPermission(resource = FulfillmentResources.VEHICLE_TYPES, action = Action.READ)
    public ApiResponse<PageResponse<VehicleTypeResponse>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(vehicleTypes.list(status, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{vehicleTypeId}")
    @Operation(summary = "One vehicle type; 404 VEHICLE_TYPE_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.VEHICLE_TYPES, action = Action.READ)
    public ApiResponse<VehicleTypeResponse> get(@PathVariable UUID vehicleTypeId) {
        return ApiResponse.ok(mapper.toResponse(vehicleTypes.get(vehicleTypeId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Declare a vehicle type (truck, pickup or container: inner dimensions and gusset clearance in mm, payload in kg); 409 "
            + "VEHICLE_TYPE_CODE_EXISTS")
    @RequiresPermission(resource = FulfillmentResources.VEHICLE_TYPES, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "vehicle_type", resourceId = "#result?.data()?.vehicleTypeId()")
    public ApiResponse<VehicleTypeResponse> create(@Valid @RequestBody VehicleTypeRequest request) {
        return ApiResponse.ok(mapper.toResponse(vehicleTypes.create(mapper.toCommand(request))));
    }

    @PutMapping("/{vehicleTypeId}")
    @Operation(summary = "Change a vehicle type (send the version read); the code never changes; status INACTIVE "
            + "retires it. 404 VEHICLE_TYPE_NOT_FOUND, 409 OPTIMISTIC_LOCK")
    @RequiresPermission(resource = FulfillmentResources.VEHICLE_TYPES, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "vehicle_type", resourceId = "#vehicleTypeId")
    public ApiResponse<VehicleTypeResponse> update(@PathVariable UUID vehicleTypeId,
                                                   @Valid @RequestBody VehicleTypeUpdateRequest request) {
        return ApiResponse.ok(mapper.toResponse(vehicleTypes.update(vehicleTypeId, mapper.toCommand(request))));
    }
}

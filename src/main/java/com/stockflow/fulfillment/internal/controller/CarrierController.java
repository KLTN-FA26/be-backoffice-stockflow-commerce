package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.CarrierRequest;
import com.stockflow.fulfillment.internal.controller.dto.CarrierResponse;
import com.stockflow.fulfillment.internal.service.Carriers;
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
 * The carrier catalogue (G4-2a; kltn-docs 09 §3). {@code fulfillment-shipments:READ} to list (dispatch staff choose
 * from it, coordinators read it); {@code fulfillment-carriers:UPDATE} to keep it - seeded for the warehouse manager
 * (spec G4 §6.4).
 */
@RestController
@RequestMapping("/api/v1/fulfillment/carriers")
@Tag(name = "Fulfillment - dispatch")
class CarrierController {

    private final Carriers carriers;
    private final DispatchWebMapper mapper;

    CarrierController(Carriers carriers, DispatchWebMapper mapper) {
        this.carriers = carriers;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Carriers, by name (paged); status ACTIVE / INACTIVE, search in code or name")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<PageResponse<CarrierResponse>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(carriers.list(status, search, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/{carrierId}")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<CarrierResponse> get(@PathVariable UUID carrierId) {
        return ApiResponse.ok(mapper.toResponse(carriers.get(carrierId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Declare a carrier; 409 CARRIER_CODE_EXISTS")
    @RequiresPermission(resource = FulfillmentResources.CARRIERS, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "carrier", resourceId = "#result?.data()?.carrierId()")
    public ApiResponse<CarrierResponse> create(@Valid @RequestBody CarrierRequest request) {
        return ApiResponse.ok(mapper.toResponse(carriers.create(new Carriers.SaveCarrier(request.code(),
                request.name(), request.contactName(), request.contactPhone(), request.status(), null))));
    }

    @PutMapping("/{carrierId}")
    @Operation(summary = "Change a carrier (send the version read); INACTIVE takes no new load. 409 OPTIMISTIC_LOCK, "
            + "CARRIER_CODE_EXISTS")
    @RequiresPermission(resource = FulfillmentResources.CARRIERS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "carrier", resourceId = "#carrierId")
    public ApiResponse<CarrierResponse> update(@PathVariable UUID carrierId,
                                               @Valid @RequestBody CarrierRequest request) {
        return ApiResponse.ok(mapper.toResponse(carriers.update(carrierId, new Carriers.SaveCarrier(request.code(),
                request.name(), request.contactName(), request.contactPhone(), request.status(),
                request.version()))));
    }
}

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
import com.stockflow.fulfillment.internal.controller.dto.CarrierServiceCodeResponse;
import com.stockflow.fulfillment.internal.controller.dto.CarrierServiceCodesRequest;
import com.stockflow.fulfillment.internal.service.CarrierServiceCodes;
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

import java.util.List;
import java.util.UUID;

/**
 * The carrier catalogue (G4-2a; kltn-docs 09 §3) and each carrier's connection settings and service codes (3-1; 09
 * §4.1 step 2). {@code fulfillment-shipments:READ} to list (dispatch staff choose from it, coordinators read it);
 * {@code fulfillment-carriers:UPDATE} to keep it - seeded for the warehouse manager (spec G4 §6.4). Secrets go in and
 * never come out: the response says whether each is set.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/carriers")
@Tag(name = "Fulfillment - dispatch")
class CarrierController {

    private final Carriers carriers;
    private final CarrierServiceCodes serviceCodes;
    private final DispatchWebMapper mapper;

    CarrierController(Carriers carriers, CarrierServiceCodes serviceCodes, DispatchWebMapper mapper) {
        this.carriers = carriers;
        this.serviceCodes = serviceCodes;
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
    @Operation(summary = "Declare a carrier, with how it connects; 409 CARRIER_CODE_EXISTS, 503 SECRETS_UNAVAILABLE")
    @RequiresPermission(resource = FulfillmentResources.CARRIERS, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "carrier", resourceId = "#result?.data()?.carrierId()")
    public ApiResponse<CarrierResponse> create(@Valid @RequestBody CarrierRequest request) {
        return ApiResponse.ok(mapper.toResponse(carriers.create(new Carriers.SaveCarrier(request.code(),
                request.name(), request.contactName(), request.contactPhone(), request.status(),
                mapper.toInput(request.connection()), null))));
    }

    @PutMapping("/{carrierId}")
    @Operation(summary = "Change a carrier (send the version read); INACTIVE takes no new load. Leave connection out to "
            + "keep it; a secret left out keeps the saved one. 409 OPTIMISTIC_LOCK, CARRIER_CODE_EXISTS, "
            + "503 SECRETS_UNAVAILABLE")
    @RequiresPermission(resource = FulfillmentResources.CARRIERS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "carrier", resourceId = "#carrierId")
    public ApiResponse<CarrierResponse> update(@PathVariable UUID carrierId,
                                               @Valid @RequestBody CarrierRequest request) {
        return ApiResponse.ok(mapper.toResponse(carriers.update(carrierId, new Carriers.SaveCarrier(request.code(),
                request.name(), request.contactName(), request.contactPhone(), request.status(),
                mapper.toInput(request.connection()), request.version()))));
    }

    @GetMapping("/{carrierId}/service-codes")
    @Operation(summary = "An API_BOOKING carrier's service code per vehicle type and province")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<List<CarrierServiceCodeResponse>> serviceCodes(@PathVariable UUID carrierId) {
        return ApiResponse.ok(serviceCodes.list(carrierId).stream().map(mapper::toResponse).toList());
    }

    @PutMapping("/{carrierId}/service-codes")
    @Operation(summary = "Replace an API_BOOKING carrier's service codes (one per vehicle type and province); "
            + "409 CARRIER_NOT_API_BOOKING, 404 VEHICLE_TYPE_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.CARRIERS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "carrier", resourceId = "#carrierId")
    public ApiResponse<List<CarrierServiceCodeResponse>> replaceServiceCodes(@PathVariable UUID carrierId,
            @Valid @RequestBody CarrierServiceCodesRequest request) {
        return ApiResponse.ok(serviceCodes.replace(carrierId, request.codes().stream()
                        .map(c -> new CarrierServiceCodes.SaveServiceCode(c.vehicleTypeId(), c.provinceCode(),
                                c.carrierCityCode(), c.serviceCode())).toList())
                .stream().map(mapper::toResponse).toList());
    }
}

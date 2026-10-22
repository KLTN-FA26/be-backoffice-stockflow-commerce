package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.LoadEstimateRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadEstimateResponse;
import com.stockflow.fulfillment.internal.service.LoadEstimates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The advisory load estimate of full-truckload load planning (SCRUM-287 PR EST; design v2 §5, LP2-1): how a released
 * order's lines would load before anything is packed. It never confirms a plan or books a vehicle. {@code
 * fulfillment-load-estimates:CREATE} to estimate, {@code :READ} to read one; every call is limited to the caller's
 * warehouses.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/load-estimates")
@Tag(name = "Fulfillment - load planning")
class LoadEstimateController {

    private final LoadEstimates estimates;
    private final LoadEstimateWebMapper mapper;

    LoadEstimateController(LoadEstimates estimates, LoadEstimateWebMapper mapper) {
        this.estimates = estimates;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Estimate how a released order loads (advisory; synchronous within the solver budget) and "
            + "make it the order's current estimate. 400 LOAD_ESTIMATE_SOURCE_UNSUPPORTED (TRANSFER), 404, 409 "
            + "LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE / VEHICLE_TYPE_INACTIVE, 422 LOAD_ESTIMATE_PACKAGING_MISSING (field "
            + "inventoryItems[<itemId>] per blocked line) / LOAD_ESTIMATE_TOO_MANY_CARTONS")
    @RequiresPermission(resource = FulfillmentResources.LOAD_ESTIMATES, action = Action.CREATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "load_estimate", resourceId = "#result?.data()?.id()")
    public ApiResponse<LoadEstimateResponse> create(@Valid @RequestBody LoadEstimateRequest request) {
        return ApiResponse.ok(mapper.toResponse(estimates.create(mapper.toCommand(request))));
    }

    @GetMapping("/{estimateId}")
    @Operation(summary = "One load estimate, current or superseded; 404 LOAD_ESTIMATE_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.LOAD_ESTIMATES, action = Action.READ)
    public ApiResponse<LoadEstimateResponse> get(@PathVariable UUID estimateId) {
        return ApiResponse.ok(mapper.toResponse(estimates.get(estimateId)));
    }

    @GetMapping
    @Operation(summary = "The current load estimate of a source (sourceType ORDER); 404 LOAD_ESTIMATE_NOT_FOUND when "
            + "it has none")
    @RequiresPermission(resource = FulfillmentResources.LOAD_ESTIMATES, action = Action.READ)
    public ApiResponse<LoadEstimateResponse> current(@RequestParam LoadEstimates.SourceType sourceType,
                                                     @RequestParam UUID sourceId) {
        return ApiResponse.ok(mapper.toResponse(estimates.current(sourceType, sourceId)));
    }
}

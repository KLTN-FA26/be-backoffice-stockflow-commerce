package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.internal.controller.dto.AssignCustomerSegmentRequest;
import com.stockflow.customer.internal.controller.dto.CreateCustomerSegmentRequest;
import com.stockflow.customer.internal.controller.dto.CustomerResponse;
import com.stockflow.customer.internal.controller.dto.CustomerSegmentResponse;
import com.stockflow.customer.internal.controller.dto.UpdateCustomerSegmentRequest;
import com.stockflow.customer.internal.service.CustomerSegmentService;
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
 * Customer groups and who is in them (user decision P7): E-commerce Admin and System Admin manage, Sales and the
 * Order Coordinator read; customers hold no grant. A group's prices are {@code /api/v1/catalog/segment-prices};
 * the customers of a group are {@code GET /api/v1/customers?segmentId=}.
 */
@RestController
@RequestMapping("/api/v1")
@PermissionResource(code = CustomerResources.SEGMENTS, group = "Customer", label = "Customer groups",
        route = "/customers/groups", apiPath = "/api/v1/customer-segments",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class CustomerSegmentController {

    private final CustomerSegmentService segments;

    CustomerSegmentController(CustomerSegmentService segments) {
        this.segments = segments;
    }

    @GetMapping("/customer-segments")
    @RequiresPermission(resource = CustomerResources.SEGMENTS, action = Action.READ)
    public ApiResponse<List<CustomerSegmentResponse>> list() {
        return ApiResponse.ok(segments.list().stream().map(CustomerSegmentController::toResponse).toList());
    }

    @GetMapping("/customer-segments/{segmentId}")
    @RequiresPermission(resource = CustomerResources.SEGMENTS, action = Action.READ)
    public ApiResponse<CustomerSegmentResponse> get(@PathVariable UUID segmentId) {
        return ApiResponse.ok(toResponse(segments.get(segmentId)));
    }

    @PostMapping("/customer-segments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = CustomerResources.SEGMENTS, action = Action.CREATE)
    public ApiResponse<CustomerSegmentResponse> create(@Valid @RequestBody CreateCustomerSegmentRequest request) {
        return ApiResponse.ok(toResponse(segments.create(request.code(), request.name(), request.description())));
    }

    @PutMapping("/customer-segments/{segmentId}")
    @RequiresPermission(resource = CustomerResources.SEGMENTS, action = Action.UPDATE)
    public ApiResponse<CustomerSegmentResponse> update(@PathVariable UUID segmentId,
                                                       @Valid @RequestBody UpdateCustomerSegmentRequest request) {
        return ApiResponse.ok(toResponse(segments.update(segmentId, request.name(), request.description(),
                request.version())));
    }

    /** Puts the customer in a group, or in none ({@code segmentId} null). */
    @PutMapping("/customers/{customerId}/segment")
    @RequiresPermission(resource = CustomerResources.SEGMENTS, action = Action.UPDATE)
    public ApiResponse<CustomerResponse> assign(@PathVariable UUID customerId,
                                                @Valid @RequestBody AssignCustomerSegmentRequest request) {
        return ApiResponse.ok(CustomerWebMapper.toResponse(segments.assign(customerId, request.segmentId(),
                request.version())));
    }

    private static CustomerSegmentResponse toResponse(CustomerSegmentService.SegmentView segment) {
        return new CustomerSegmentResponse(segment.id(), segment.code(), segment.name(), segment.description(),
                segment.customerCount(), segment.version());
    }
}

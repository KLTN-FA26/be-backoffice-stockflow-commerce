package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.ChangeCustomerStatusCommand;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerStatusEntry;
import com.stockflow.customer.api.ListCustomersQuery;
import com.stockflow.customer.api.UpdateCustomerCommand;
import com.stockflow.customer.internal.controller.dto.ChangeCustomerStatusRequest;
import com.stockflow.customer.internal.controller.dto.CustomerResponse;
import com.stockflow.customer.internal.controller.dto.UpdateCustomerRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@PermissionResource(code = CustomerResources.CUSTOMERS, group = "Customer", label = "Customers",
        route = "/admin/customers", apiPath = "/api/v1/customers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/me")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<CustomerResponse> me(@AuthenticatedUser CurrentUser user) {
        return customers.findByUserId(user.userId()).map(CustomerWebMapper::toMaskedResponse)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    @PutMapping("/me")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.UPDATE)
    public ApiResponse<CustomerResponse> updateMe(@AuthenticatedUser CurrentUser user,
                                                  @Valid @RequestBody UpdateCustomerRequest request) {
        var current = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return ApiResponse.ok(CustomerWebMapper.toMaskedResponse(customers.update(new UpdateCustomerCommand(
                current.customerId(), user.userId(), false, request.fullName(), request.phone(),
                request.version()))));
    }

    @GetMapping
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<PageResponse<CustomerResponse>> list(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) UUID segmentId) {
        requireStaff(user);
        var query = new ListCustomersQuery(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                search, status, sort, segmentId);
        return ApiResponse.ok(customers.list(query).map(CustomerWebMapper::toMaskedResponse));
    }

    @GetMapping("/{customerId}")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<CustomerResponse> findOne(@PathVariable UUID customerId,
                                                 @AuthenticatedUser CurrentUser user) {
        requireStaff(user);
        return customers.findById(customerId).map(CustomerWebMapper::toResponse).map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    @PutMapping("/{customerId}")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.UPDATE)
    public ApiResponse<CustomerResponse> update(@PathVariable UUID customerId,
                                                @AuthenticatedUser CurrentUser user,
                                                @Valid @RequestBody UpdateCustomerRequest request) {
        requireStaff(user);
        return ApiResponse.ok(CustomerWebMapper.toResponse(customers.update(new UpdateCustomerCommand(
                customerId, user.userId(), true, request.fullName(), request.phone(), request.version(),
                request.taxCode()))));
    }

    @PostMapping("/{customerId}/status")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.APPROVE)
    public ApiResponse<CustomerResponse> changeStatus(@PathVariable UUID customerId,
                                                      @AuthenticatedUser CurrentUser user,
                                                      @Valid @RequestBody ChangeCustomerStatusRequest request) {
        if (!user.hasRole(Role.ECOMMERCE_ADMIN)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return ApiResponse.ok(CustomerWebMapper.toMaskedResponse(customers.changeStatus(new ChangeCustomerStatusCommand(
                customerId, request.status(), request.reason(), user.userId()))));
    }

    @GetMapping("/{customerId}/status-history")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<List<CustomerStatusEntry>> statusHistory(@PathVariable UUID customerId,
                                                                @AuthenticatedUser CurrentUser user) {
        requireStaff(user);
        return ApiResponse.ok(customers.statusHistory(customerId));
    }

    /** Decision 2026-10-17: every change of the company name and the tax code, for staff (values whole). */
    @GetMapping("/{customerId}/change-history")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<List<com.stockflow.customer.api.CustomerChangeEntry>> changeHistory(@PathVariable UUID customerId,
                                                                                          @AuthenticatedUser CurrentUser user) {
        requireStaff(user);
        return ApiResponse.ok(customers.changeHistory(customerId));
    }

    private static void requireStaff(CurrentUser user) {
        if (!user.hasAnyRole(Role.ECOMMERCE_ADMIN, Role.SALES_STAFF)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}

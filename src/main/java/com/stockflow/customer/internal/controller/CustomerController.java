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
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.ChangeCustomerStatusCommand;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerStatusEntry;
import com.stockflow.customer.api.CustomerSummary;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@PermissionResource(code = CustomerResources.CUSTOMERS, group = "Customer", label = "Customers",
        route = "/admin/customers", apiPath = "/api/v1/customers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class CustomerController {

    private final CustomerService customers;
    private final com.stockflow.customer.internal.service.CustomerLegalProfileService legalProfiles;
    private final com.stockflow.customer.internal.service.SalesOwnerViews owners;

    CustomerController(CustomerService customers,
                       com.stockflow.customer.internal.service.CustomerLegalProfileService legalProfiles,
                       com.stockflow.customer.internal.service.SalesOwnerViews owners) {
        this.customers = customers;
        this.legalProfiles = legalProfiles;
        this.owners = owners;
    }

    @GetMapping("/me")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<CustomerResponse> me(@AuthenticatedUser CurrentUser user) {
        return customers.findByUserId(user.userId()).map(CustomerWebMapper::toMaskedResponse)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    /**
     * The signed-in customer's own delivery addresses, the same answer as {@code GET /{customerId}/addresses} for their
     * own id: the customer resolved from the sign-in, never from the request, so the storefront need not read
     * {@code /me} first only to learn its id. Read with {@code customer-addresses:READ}, the grant the storefront
     * already reads its addresses with; 404 {@code CUSTOMER_NOT_FOUND} for an account with no customer record.
     */
    @GetMapping("/me/addresses")
    @io.swagger.v3.oas.annotations.Operation(summary = "The signed-in customer's own delivery addresses")
    @RequiresPermission(resource = CustomerResources.ADDRESSES, action = Action.READ)
    public ApiResponse<List<com.stockflow.customer.internal.controller.dto.CustomerAddressResponse>> myAddresses(
            @AuthenticatedUser CurrentUser user) {
        var own = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return ApiResponse.ok(customers.listAddresses(own.customerId(), user.userId(), false).stream()
                .map(CustomerWebMapper::toResponse).toList());
    }

    /**
     * R5-10: the signed-in customer's own legal profile, read-only - the customer resolved from the sign-in, never from
     * the request, so no one reads another customer's. Read with {@code customers:READ}, the grant that already reads
     * their own record at {@code /me}; the profile's own grant ({@code customer-legal-profiles}) stays the back office's.
     * {@code data: null} while none is recorded.
     */
    @GetMapping("/me/legal-profile")
    @io.swagger.v3.oas.annotations.Operation(summary = "The signed-in customer's own legal profile, as their contracts "
            + "and e-invoices name them; data null while none is recorded")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<com.stockflow.customer.internal.controller.dto.OwnLegalProfileResponse> myLegalProfile(
            @AuthenticatedUser CurrentUser user) {
        var own = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return ApiResponse.ok(legalProfiles.legalProfile(own.customerId()).map(profile -> {
            var bank = profile.bankAccount();
            return new com.stockflow.customer.internal.controller.dto.OwnLegalProfileResponse(profile.companyName(),
                    profile.taxCode(), profile.registeredAddress(), profile.representativeName(),
                    profile.representativeTitle(), profile.invoiceEmail(), bank == null ? null : bank.accountNumber(),
                    bank == null ? null : bank.bankName(), bank == null ? null : bank.accountHolder());
        }).orElse(null));
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
            @RequestParam(required = false) UUID segmentId,
            @RequestParam(required = false) UUID salesOwnerId,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) String salesOwnerState,
            @RequestParam(required = false) Boolean taxCodeMissing) {
        requireStaff(user);
        // Sales-owner plan BR-S11: "my customers" is the caller's own; the contract's other filters as given.
        var query = new ListCustomersQuery(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                search, status, sort, segmentId, Boolean.TRUE.equals(mine) ? user.userId() : salesOwnerId,
                ownerState(salesOwnerState), taxCodeMissing);
        var found = customers.list(query);
        var columns = owners.columns(found.items().stream()
                .map(com.stockflow.customer.api.CustomerSummary::customerId).toList());
        // The province is an address fact: only a caller who may read addresses is told it, so this list shows no more
        // than GET /customers/{id}/addresses would (customer-addresses:READ).
        Map<UUID, String> provinces = user.can(CustomerResources.ADDRESSES, Action.READ) && !found.items().isEmpty()
                ? customers.defaultShippingProvinceNames(found.items().stream()
                        .map(CustomerSummary::customerId).toList())
                : Map.of();
        return ApiResponse.ok(found.map(summary -> CustomerWebMapper.toListRow(summary,
                columns.get(summary.customerId()), provinces.get(summary.customerId()))));
    }

    private static com.stockflow.customer.api.SalesOwnerState ownerState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return com.stockflow.customer.api.SalesOwnerState.valueOf(value.strip());
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown sales owner state " + value);
        }
    }

    @GetMapping("/{customerId}")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<CustomerResponse> findOne(@PathVariable UUID customerId,
                                                 @AuthenticatedUser CurrentUser user) {
        requireStaff(user);
        return customers.findById(customerId)
                .map(summary -> CustomerWebMapper.toResponse(summary, owners.columns(List.of(customerId))
                        .get(customerId)))
                .map(ApiResponse::ok)
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

    /** Activating, suspending or closing a customer account: its own resource, seeded for the Sales Manager. */
    @PostMapping("/{customerId}/status")
    @RequiresPermission(resource = CustomerResources.STATUS, action = Action.APPROVE)
    public ApiResponse<CustomerResponse> changeStatus(@PathVariable UUID customerId,
                                                      @AuthenticatedUser CurrentUser user,
                                                      @Valid @RequestBody ChangeCustomerStatusRequest request) {
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

    /**
     * Data scope: a portal customer holds customer-customers:READ / UPDATE for its own profile ({@code /me}); every
     * other customer's record is the back office's.
     */
    private static void requireStaff(CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
    }
}

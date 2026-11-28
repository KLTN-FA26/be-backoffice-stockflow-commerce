package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.internal.controller.dto.SalesOwnerRequests;
import com.stockflow.customer.internal.domain.OwnerChangeRequest;
import com.stockflow.customer.internal.service.OwnerChangeRequestService;
import com.stockflow.customer.internal.service.SalesOwnerViews;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The portal's "your salesperson" card and a customer's request to change salesperson, and the managers' side of it
 * (sales-owner plan §10.5, BR-S15, D11, D12).
 *
 * <p>The {@code /customers/me/...} endpoints resolve the customer from the sign-in, never from the request: a portal
 * account reads and asks about its own customer only. The managers' list and decisions refuse a portal account even
 * though it holds {@code sales-owner-change-requests:READ} for its own requests. A salesperson holds none of these
 * permissions: the current owner learns of a request only once it is decided.</p>
 */
@RestController
class OwnerChangeRequestController {

    private final OwnerChangeRequestService requests;
    private final SalesOwnerViews views;

    OwnerChangeRequestController(OwnerChangeRequestService requests, SalesOwnerViews views) {
        this.requests = requests;
        this.views = views;
    }

    /** The signed-in customer's salesperson: a name, whether they work now, the cover's name. No e-mail or phone. */
    @GetMapping("/api/v1/customers/me/sales-owner")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<SalesOwnerViews.PortalOwner> mySalesOwner(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(views.ofCustomerAccount(user.userId()));
    }

    /** The salespeople the customer may name in a request (D11): names only, the current owner left out. */
    @GetMapping("/api/v1/customers/me/sales-options")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<List<SalesOwnerViews.SalesOption>> mySalesOptions(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(views.salesOptions(user.userId()));
    }

    @PostMapping("/api/v1/customers/me/sales-owner-change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = CustomerResources.OWNER_CHANGE_REQUESTS, action = Action.CREATE)
    public ApiResponse<OwnerChangeRequestService.OwnRow> ask(@AuthenticatedUser CurrentUser user,
                                                             @Valid @RequestBody SalesOwnerRequests.AskOwnerChange request) {
        return ApiResponse.ok(requests.ask(user.userId(), request.reason(), request.note(),
                request.preferredSalesUserId()));
    }

    @GetMapping("/api/v1/customers/me/sales-owner-change-requests")
    @RequiresPermission(resource = CustomerResources.OWNER_CHANGE_REQUESTS, action = Action.READ)
    public ApiResponse<List<OwnerChangeRequestService.OwnRow>> mine(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(requests.mine(user.userId()));
    }

    @GetMapping("/api/v1/sales-owner-change-requests")
    @RequiresPermission(resource = CustomerResources.OWNER_CHANGE_REQUESTS, action = Action.READ)
    public ApiResponse<PageResponse<OwnerChangeRequestService.ManagerRow>> list(
            @AuthenticatedUser CurrentUser user, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(requests.list(status(status), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size));
    }

    @PostMapping("/api/v1/sales-owner-change-requests/{requestId}/approval")
    @RequiresPermission(resource = CustomerResources.OWNER_CHANGE_REQUESTS, action = Action.APPROVE)
    public ApiResponse<OwnerChangeRequestService.ManagerRow> approve(
            @PathVariable UUID requestId, @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody SalesOwnerRequests.ApproveOwnerChange request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(requests.approve(requestId, request.newSalesOwnerId(), request.note(), user.userId()));
    }

    @PostMapping("/api/v1/sales-owner-change-requests/{requestId}/rejection")
    @RequiresPermission(resource = CustomerResources.OWNER_CHANGE_REQUESTS, action = Action.APPROVE)
    public ApiResponse<OwnerChangeRequestService.ManagerRow> reject(
            @PathVariable UUID requestId, @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody SalesOwnerRequests.RejectOwnerChange request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(requests.reject(requestId, request.note(), user.userId()));
    }

    private static OwnerChangeRequest.Status status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OwnerChangeRequest.Status.valueOf(value.strip());
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown request status " + value);
        }
    }
}

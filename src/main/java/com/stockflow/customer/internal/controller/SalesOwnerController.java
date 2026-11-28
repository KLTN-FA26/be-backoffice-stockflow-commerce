package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.customer.internal.controller.dto.SalesOwnerRequests;
import com.stockflow.customer.internal.service.SalesOwnerTransferService;
import com.stockflow.customer.internal.service.SalesOwnerViews;
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

/**
 * A customer's Sales owner over HTTP (sales-owner plan BR-S01..S11, §10.1; API contract "BE-A"): the owner card and
 * its change, the history, bulk and temporary transfers and their return, the salespeople a manager picks from, and the
 * duplicate warning of the create form.
 *
 * <p>Reading needs {@code customer-sales-owners:READ} (Sales, manager, accountant: BR-S11 keeps reading open);
 * changing, transferring and listing transfers need {@code customer-sales-owners:UPDATE}, the Sales Manager's alone
 * (BR-S01). The rules of each change are {@link SalesOwnership}'s and the transfer service's.</p>
 */
@RestController
@RequestMapping("/api/v1/customers")
class SalesOwnerController {

    private final SalesOwnership ownership;
    private final SalesOwnerViews views;
    private final SalesOwnerTransferService transfers;

    SalesOwnerController(SalesOwnership ownership, SalesOwnerViews views, SalesOwnerTransferService transfers) {
        this.ownership = ownership;
        this.views = views;
        this.transfers = transfers;
    }

    @GetMapping("/{customerId}/sales-owner")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.READ)
    public ApiResponse<SalesOwnerViews.OwnerView> owner(@PathVariable UUID customerId,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(views.owner(customerId));
    }

    /** BR-S01, BR-S03, BR-S04, case 15: a manager changes the owner, with a reason and the version read. */
    @PutMapping("/{customerId}/sales-owner")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.UPDATE)
    public ApiResponse<SalesOwnerViews.OwnerView> changeOwner(@PathVariable UUID customerId,
                                                              @AuthenticatedUser CurrentUser user,
                                                              @Valid @RequestBody SalesOwnerRequests.AssignSalesOwner request) {
        ScopeGuards.requireBackOffice(user);
        ownership.assign(new AssignSalesOwnerCommand(customerId, request.salesOwnerId(), request.reason(),
                request.note(), request.notifyCustomer(), request.version(), user.userId()));
        return ApiResponse.ok(views.owner(customerId));
    }

    @GetMapping("/{customerId}/sales-owner-history")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.READ)
    public ApiResponse<PageResponse<SalesOwnerViews.HistoryRow>> history(@PathVariable UUID customerId,
                                                                        @AuthenticatedUser CurrentUser user,
                                                                        @RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(views.history(customerId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size));
    }

    /** BR-S09 / §10.1: idempotent on {@code requestId}. */
    @PostMapping("/sales-owner-transfers")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.UPDATE)
    public ApiResponse<SalesOwnerTransferService.Result> transfer(@AuthenticatedUser CurrentUser user,
                                                                  @Valid @RequestBody SalesOwnerRequests.Transfer request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(transfers.transfer(new SalesOwnerTransferService.Transfer(request.requestId(),
                request.fromUserId(), request.toUserIds(), request.customerIds(), request.reason(), request.note(),
                request.temporary(), request.notifyCustomer()), user.userId()));
    }

    /** §10.1: a temporary batch's customers back to the salesperson who returned, in one step. */
    @PostMapping("/sales-owner-transfers/{batchId}/return")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.UPDATE)
    public ApiResponse<SalesOwnerTransferService.Result> returnBatch(@PathVariable UUID batchId,
                                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(transfers.returnBatch(batchId, user.userId()));
    }

    @GetMapping("/sales-owner-transfers")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.UPDATE)
    public ApiResponse<List<SalesOwnerTransferService.BatchRow>> transfers(
            @AuthenticatedUser CurrentUser user, @RequestParam(defaultValue = "false") boolean temporary) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(transfers.list(temporary));
    }

    /** D42: every owner and cover picker offers the active SALES_STAFF only. */
    @GetMapping("/sales-agents")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.READ)
    public ApiResponse<List<SalesOwnerViews.SalesAgent>> salesAgents(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(views.salesAgents());
    }

    /** BR-S14 / case 16: who a new customer may duplicate, and who looks after them. */
    @GetMapping("/duplicates")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<List<SalesOwnerViews.Duplicate>> duplicates(@AuthenticatedUser CurrentUser user,
                                                                  @RequestParam(required = false) String taxCode,
                                                                  @RequestParam(required = false) String phone,
                                                                  @RequestParam(required = false) String email,
                                                                  @RequestParam(required = false) String name) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(views.duplicates(taxCode, phone, email, name));
    }
}

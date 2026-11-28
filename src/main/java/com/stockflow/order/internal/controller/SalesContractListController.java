package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.service.SalesContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The back office's contract page (contract e-signing plan, 2026-11-28): every order's contract in one list with its
 * filters, and the tiles over it. Read with {@code sales-contracts:READ}, as the contract of one order is; the Sales
 * owner shown is the one recorded when the contract was drawn up (14 BR-26), and "mine" is that owner. Handlers are public
 * so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/sales-contracts")
@Tag(name = "Orders")
class SalesContractListController {

    private final SalesContracts contracts;

    SalesContractListController(SalesContracts contracts) {
        this.contracts = contracts;
    }

    @GetMapping
    @Operation(summary = "The contracts of every order, newest first. Filters: status (repeatable), salesOwnerId or "
            + "mine=true (the Sales owner recorded on the contract), signByBefore (contracts waiting for the customer "
            + "whose signing deadline is before the day), termsReview (NONE, PENDING, APPROVED, REJECTED), q (a part of "
            + "the contract's or the order's number, or of the buyer's name)")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<PageResponse<SalesContracts.ContractRow>> list(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID salesOwnerId,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signByBefore,
            @RequestParam(required = false) String termsReview,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.list(new SalesContracts.ContractQuery(
                status == null ? Set.of() : new LinkedHashSet<>(status), Boolean.TRUE.equals(mine) ? user.userId()
                : salesOwnerId, signByBefore, termsReview, search, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)));
    }

    @GetMapping("/summary")
    @Operation(summary = "How many contracts are in each status, how many wait for the company's signature (the customer "
            + "has signed), how many are past their signing deadline and how many drafts wait for their terms to be "
            + "approved")
    @RequiresPermission(resource = OrderResources.SALES_CONTRACTS, action = Action.READ)
    public ApiResponse<SalesContracts.ContractSummary> summary(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(contracts.summary());
    }
}

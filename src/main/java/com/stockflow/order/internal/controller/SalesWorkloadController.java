package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.controller.dto.SalesWorkloadResponse;
import com.stockflow.order.internal.service.SalesWorkloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Sales Managers' workload board (sales-owner plan §10.4, screen "Phân công khách"). Guarded by
 * {@code sales-workload:READ} - the Sales Manager's and the admin's (V20270128000140), declared by the customer module
 * ({@code SalesWorkloadResourceDeclaration}) - and served here because most of its figures are this module's documents.
 */
@RestController
@RequestMapping("/api/v1/sales")
@Tag(name = "Sales owners")
class SalesWorkloadController {

    private final SalesWorkloadService workload;

    SalesWorkloadController(SalesWorkloadService workload) {
        this.workload = workload;
    }

    @GetMapping("/workload")
    @Operation(summary = "Per active salesperson: customers owned, customers with an order under way, open quotes, "
            + "contracts awaiting signature, sales of the last 30 days, new customers this month, assignment settings and "
            + "whether someone covers for them today",
            description = "Active salespeople only (a Sales Manager or an admin never owns a customer, D42), by name. "
                    + "revenue30d counts contracts signed in the last 30 days by the owner recorded on the contract "
                    + "when it was drawn up (kltn-docs 14 BR-26). A constant number of reads, whatever the number of "
                    + "salespeople.")
    @RequiresPermission(resource = OrderResources.SALES_WORKLOAD, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<SalesWorkloadResponse>> workload(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(workload.workload().stream().map(row -> new SalesWorkloadResponse(row.userId(),
                row.name(), row.paused(), row.capacityPerMonth(), row.onLeave(), row.coverUserId(), row.coverName(),
                row.coverUntil(), row.customers(), row.customersWithOpenOrders(), row.openQuotes(),
                row.contractsAwaitingSignature(), row.revenue30d(), row.newCustomersThisMonth())).toList());
    }
}

package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.payment.internal.controller.dto.OrderPaymentsResponse;
import com.stockflow.payment.internal.service.MoneyLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Payments of an order, as the ledger has them (G2-3): what the accountant reconciles, what Sales reminds the customer
 * of (kltn-docs 15 §2), and what the coordinator checks before a release or a handover. Its own permission resource:
 * reading one order's money does not open the list of every customer's transfers.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Receivables")
@PermissionResource(code = PaymentResources.ORDER_PAYMENTS, group = "Payment", label = "Order payments",
        route = "/payment/order-payments", apiPath = "/api/v1/payments/orders", actions = {Action.READ})
class PaymentController {

    private final MoneyLedgerService ledger;

    PaymentController(MoneyLedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "An order's payment history: allocations and reversals, receivables, refunds, and what it "
            + "may still take by transfer")
    @RequiresPermission(resource = PaymentResources.ORDER_PAYMENTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<OrderPaymentsResponse> ofOrder(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(PaymentWebMapper.toResponse(ledger.orderPayments(orderId)));
    }
}

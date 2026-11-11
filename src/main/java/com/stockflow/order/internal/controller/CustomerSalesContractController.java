package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.service.SalesContracts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The contract of one of the signed-in customer's own orders, once Sales has sent it (4B-4; kltn-docs 14 §4.3 step 13:
 * the customer reads the contract on the portal). The customer is resolved from the sign-in, never from the request;
 * someone else's order, a draft or no contract is 404 either way. Handlers are public so the permission proxy advises
 * them.
 */
@RestController
@RequestMapping("/api/v1/orders/me/{orderId}/contract")
@Tag(name = "Orders")
class CustomerSalesContractController {

    private final SalesContracts contracts;
    private final CustomerService customers;

    CustomerSalesContractController(SalesContracts contracts, CustomerService customers) {
        this.contracts = contracts;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "The contract of one of the signed-in customer's own orders, once sent")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<SalesContracts.ContractView> get(@PathVariable UUID orderId,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerContract(ownCustomerId(user), orderId));
    }

    @GetMapping("/document")
    @Operation(summary = "A short-lived link to the contract as sent, or as signed once it is")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> document(@PathVariable UUID orderId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerDocument(ownCustomerId(user), orderId));
    }

    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}

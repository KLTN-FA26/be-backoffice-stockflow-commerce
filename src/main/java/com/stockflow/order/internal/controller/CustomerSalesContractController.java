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
import com.stockflow.order.internal.controller.dto.SalesContractRequests;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    @Operation(summary = "A short-lived link to the contract as signed once it is, else as sent; copy=sent asks for the "
            + "PDF as sent even after signing")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> document(@PathVariable UUID orderId,
                                              @RequestParam(name = "copy", required = false) String copy,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerDocument(ownCustomerId(user), orderId, "sent".equalsIgnoreCase(copy)));
    }

    @GetMapping("/revisions")
    @Operation(summary = "The revisions of the contract that were sent to the signed-in customer, with what they asked "
            + "for after reading each; no sender is named")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<java.util.List<SalesContracts.RevisionView>> revisions(@PathVariable UUID orderId,
                                                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerRevisions(ownCustomerId(user), orderId));
    }

    @GetMapping("/revisions/{revision}/document")
    @Operation(summary = "A short-lived link to the PDF that revision was sent as")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<DownloadLink> revisionDocument(@PathVariable UUID orderId, @PathVariable int revision,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.customerRevisionDocument(ownCustomerId(user), orderId, revision));
    }

    @PostMapping("/change-request")
    @Operation(summary = "Send the contract back for changes: what to change, 10 to 2000 characters. SENT -> DRAFT as the "
            + "next revision (the sent PDF is kept as the revision's), and the salesperson who looks after the "
            + "customer is told. 409 SALES_CONTRACT_STATUS_INVALID unless the contract is sent and not yet signed by "
            + "the customer; 409 OPTIMISTIC_LOCK for a stale version. The answer is the contract as the portal shows it: "
            + "status CHANGES_REQUESTED")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<SalesContracts.ContractView> requestChanges(@PathVariable UUID orderId,
                                                                   @Valid @RequestBody
                                                                   SalesContractRequests.ChangeRequest request,
                                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(contracts.requestChanges(ownCustomerId(user), user.userId(), orderId, request.version(),
                request.reason()));
    }

    private UUID ownCustomerId(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND))
                .customerId();
    }
}

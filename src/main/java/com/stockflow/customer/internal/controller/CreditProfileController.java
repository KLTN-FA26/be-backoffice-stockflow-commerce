package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.customer.internal.controller.dto.CreditProfileResponse;
import com.stockflow.customer.internal.controller.dto.SaveCreditProfileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A customer's commercial terms (SCRUM-427, kltn-docs 18 §2-3). Sales reads them; only whoever
 * approves credit changes them ({@code APPROVE}, 18 BR-02) and every change is audited.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/credit-profile")
@Tag(name = "Customers")
@PermissionResource(code = CustomerResources.CREDIT_PROFILES, group = "Customer", label = "Commercial terms",
        route = "/customers", apiPath = "/api/v1/customers/{customerId}/credit-profile",
        actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
class CreditProfileController {

    private final CustomerService customers;

    CreditProfileController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "The customer's payment terms, deposit share, credit limit and days to pay")
    @RequiresPermission(resource = CustomerResources.CREDIT_PROFILES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<CreditProfileResponse> get(@PathVariable UUID customerId) {
        return ApiResponse.ok(toResponse(customers.creditTerms(customerId)));
    }

    @PutMapping
    @Operation(summary = "Set the customer's commercial terms (credit approver only)")
    @RequiresPermission(resource = CustomerResources.CREDIT_PROFILES, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<CreditProfileResponse> save(@PathVariable UUID customerId,
                                                   @Valid @RequestBody SaveCreditProfileRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(customers.saveCreditTerms(new SaveCreditTermsCommand(customerId,
                request.allowPrepaid(), request.allowDeposit(), request.allowCredit(), request.defaultPaymentTerm(),
                request.depositPercent(), request.creditLimit(), request.creditTermDays(), request.note(),
                user.userId(), request.expectedVersion()))));
    }

    static CreditProfileResponse toResponse(CreditTerms t) {
        return new CreditProfileResponse(t.customerId(), t.configured(), t.allowPrepaid(), t.allowDeposit(),
                t.allowCredit(), t.defaultTerm().name(), t.depositPercent(), t.creditLimit(), t.creditTermDays(),
                t.currency(), t.approvedBy(), t.approvedAt(), t.note(), t.configured() ? t.version() : -1L);
    }
}

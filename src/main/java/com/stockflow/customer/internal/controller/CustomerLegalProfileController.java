package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.internal.controller.dto.LegalProfileResponse;
import com.stockflow.customer.internal.controller.dto.SaveLegalProfileRequest;
import com.stockflow.customer.internal.service.CustomerLegalProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A customer's legal profile (4B-4; WF-05 E1; kltn-docs 18 §3, BR-05, BR-12; QD-14): the registered business address
 * and the legal representative a sales contract names the buyer by, the e-mail address its e-invoices are sent to and
 * its bank account. Sales' and the accountant's, under a grant of its own ({@code customer-legal-profiles}): before
 * WF-05 E1 it took the customer record's grants, which the accountant does not hold for changes. Every change is in
 * the customer's change history ({@code GET /customers/{id}/change-history}). Handlers are public so the permission
 * proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/legal-profile")
@Tag(name = "Customers")
@PermissionResource(code = CustomerResources.LEGAL_PROFILES, group = "Customer", label = "Legal profiles",
        route = "/customers", apiPath = "/api/v1/customers/{customerId}/legal-profile",
        actions = {Action.READ, Action.UPDATE})
class CustomerLegalProfileController {

    private final CustomerLegalProfileService profiles;

    CustomerLegalProfileController(CustomerLegalProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "The customer's legal profile; 404 LEGAL_PROFILE_NOT_FOUND while it is not recorded")
    @RequiresPermission(resource = CustomerResources.LEGAL_PROFILES, action = Action.READ)
    public ApiResponse<LegalProfileResponse> get(@PathVariable UUID customerId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(profiles.legalProfile(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEGAL_PROFILE_NOT_FOUND))));
    }

    @PutMapping
    @Operation(summary = "Record the customer's registered address, legal representative and title, invoice e-mail "
            + "(absent: the contact e-mail) and bank account (absent: none), whole; send the version read (none for "
            + "the first). Contracts and invoices already drawn up keep what they copied")
    @RequiresPermission(resource = CustomerResources.LEGAL_PROFILES, action = Action.UPDATE)
    public ApiResponse<LegalProfileResponse> save(@PathVariable UUID customerId,
                                                  @RequestBody SaveLegalProfileRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        // Not @Valid: a caller that is not staff is turned away (404) before anything about the body is said; the
        // service checks every field.
        ScopeGuards.requireBackOffice(user);
        SaveLegalProfileRequest.BankAccount bank = request.bankAccount();
        return ApiResponse.ok(toResponse(profiles.save(new CustomerLegalProfileService.Save(customerId,
                request.registeredAddress(), request.representativeName(), request.representativeTitle(),
                request.invoiceEmail(), bank == null ? null : new CustomerLegalProfile.BankAccount(
                        bank.accountNumber(), bank.bankName(), bank.accountHolder()),
                request.version(), user.userId()))));
    }

    private static LegalProfileResponse toResponse(CustomerLegalProfile profile) {
        CustomerLegalProfile.BankAccount bank = profile.bankAccount();
        return new LegalProfileResponse(profile.customerId(), profile.companyName(), profile.taxCode(),
                profile.registeredAddress(), profile.representativeName(), profile.representativeTitle(),
                profile.invoiceEmail(), bank == null ? null : new LegalProfileResponse.BankAccount(
                        bank.accountNumber(), bank.bankName(), bank.accountHolder()), profile.version());
    }
}

package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerLegalProfile;
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
 * A customer's legal profile (4B-4; kltn-docs 18 §3, BR-05; QD-14): the registered business address and the legal
 * representative a sales contract names the buyer by. The back office's, under the customer record's own grants.
 * Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/legal-profile")
@Tag(name = "Customers")
class CustomerLegalProfileController {

    private final CustomerLegalProfileService profiles;

    CustomerLegalProfileController(CustomerLegalProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "The customer's legal profile; 404 LEGAL_PROFILE_NOT_FOUND while it is not recorded")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<CustomerLegalProfile> get(@PathVariable UUID customerId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(profiles.legalProfile(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEGAL_PROFILE_NOT_FOUND)));
    }

    @PutMapping
    @Operation(summary = "Record the customer's registered address, legal representative and title, whole; send the "
            + "version read (none for the first). Contracts already drawn up keep what they copied")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.UPDATE)
    public ApiResponse<CustomerLegalProfile> save(@PathVariable UUID customerId,
                                                  @RequestBody SaveLegalProfileRequest request,
                                                  @AuthenticatedUser CurrentUser user) {
        // Not @Valid: a portal customer holds customer-customers:UPDATE for its own profile, and must be turned away
        // (404) before anything about the body is said; the service checks every field.
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(profiles.save(new CustomerLegalProfileService.Save(customerId,
                request.registeredAddress(), request.representativeName(), request.representativeTitle(),
                request.version(), user.userId())));
    }
}

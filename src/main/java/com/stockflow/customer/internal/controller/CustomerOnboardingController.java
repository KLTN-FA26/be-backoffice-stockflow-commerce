package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.customer.internal.controller.dto.ActivatePortalAccountRequest;
import com.stockflow.customer.internal.controller.dto.CreateBusinessCustomerRequest;
import com.stockflow.customer.internal.controller.dto.InvitedCustomerResponse;
import com.stockflow.customer.internal.controller.dto.PortalActivationResponse;
import com.stockflow.customer.internal.controller.dto.PortalInvitationResponse;
import com.stockflow.customer.internal.service.CustomerOnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * B2B onboarding over HTTP (SCRUM-450): Sales creates the customer and (re)sends the portal
 * invitation; the contact activates the account from the link.
 *
 * <p>Creating a customer and (re)sending its invitation need {@code customer-customers:CREATE}, seeded
 * for SALES_STAFF, ECOMMERCE_ADMIN and SYSTEM_ADMIN by V20261014000100 - a permission rather than a role
 * check, so a custom role an administrator grants it to works too (SCRUM-453). A caller holding the
 * CUSTOMER role is refused regardless: no customer onboards another (BR-01a). Reading the invitation
 * state keeps the Sales/e-commerce-admin check of the other staff reads in this module, because
 * CUSTOMER holds {@code READ} for its own profile.</p>
 *
 * <p>Activation is anonymous by nature (the contact has no password yet) and is opened for POST alone
 * in the security configuration. The token is the authorisation; the rate limit is per address.</p>
 */
@RestController
@RequestMapping("/api/v1/customers")
class CustomerOnboardingController {

    private final CustomerOnboardingService onboarding;

    CustomerOnboardingController(CustomerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.CREATE)
    @RateLimit(limit = 30, perSeconds = 60)
    public ApiResponse<InvitedCustomerResponse> create(@AuthenticatedUser CurrentUser user,
                                                       @Valid @RequestBody CreateBusinessCustomerRequest request) {
        refuseCustomers(user);
        return ApiResponse.ok(toResponse(onboarding.create(new CustomerOnboardingService.CreateBusinessCustomer(
                request.companyName(), request.taxCode(), request.contactName(), request.email(), request.phone(),
                user.userId(), request.paymentTerm(), request.depositPercent()))));
    }

    @PostMapping("/{customerId}/portal-invitation")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.CREATE)
    @RateLimit(limit = 10, perSeconds = 60)
    public ApiResponse<InvitedCustomerResponse> resend(@PathVariable UUID customerId,
                                                       @AuthenticatedUser CurrentUser user) {
        refuseCustomers(user);
        return ApiResponse.ok(toResponse(onboarding.resendInvitation(customerId, user.userId())));
    }

    @GetMapping("/{customerId}/portal-invitation")
    @RequiresPermission(resource = CustomerResources.CUSTOMERS, action = Action.READ)
    public ApiResponse<PortalInvitationResponse> invitation(@PathVariable UUID customerId,
                                                            @AuthenticatedUser CurrentUser user) {
        requireSales(user);
        var view = onboarding.invitation(customerId);
        return ApiResponse.ok(new PortalInvitationResponse(view.customerId(), view.state().name(),
                view.issuedAt(), view.expiresAt(), view.delivery()));
    }

    @PostMapping("/portal-activation")
    @RateLimit(limit = 10, perSeconds = 60, key = RateLimit.Key.IP)
    public ApiResponse<PortalActivationResponse> activate(@Valid @RequestBody ActivatePortalAccountRequest request) {
        return ApiResponse.ok(new PortalActivationResponse(onboarding.activate(request.token(), request.password())));
    }

    private static void refuseCustomers(CurrentUser user) {
        if (user.roles().contains(Role.CUSTOMER)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private static void requireSales(CurrentUser user) {
        if (!user.hasAnyRole(Role.ECOMMERCE_ADMIN, Role.SALES_STAFF)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static InvitedCustomerResponse toResponse(CustomerOnboardingService.Invited invited) {
        return new InvitedCustomerResponse(CustomerWebMapper.toResponse(invited.customer()),
                new InvitedCustomerResponse.Invitation(invited.invitation().issuedAt(),
                        invited.invitation().expiresAt(), invited.delivery().name()));
    }
}

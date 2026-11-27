package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.internal.controller.dto.SalesOwnerRequests;
import com.stockflow.customer.internal.service.SalesAssignmentSettingsService;
import com.stockflow.customer.internal.service.SalesOwnerViews;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What the managers' assignment screen reads and sets about a salesperson (sales-owner plan §10.4, D13), and the lock
 * dialog's summary of what a salesperson leaves behind (BO-2). Lives in customer because what it counts is customer's:
 * identity may not depend on customer, customer depends on identity.
 */
@RestController
class SalesStaffController {

    /** Identity's user-management resource: the lock dialog is user management's screen. */
    private static final String IDENTITY_USERS = "identity-users";

    private final SalesAssignmentSettingsService settings;
    private final SalesOwnerViews views;

    SalesStaffController(SalesAssignmentSettingsService settings, SalesOwnerViews views) {
        this.settings = settings;
        this.views = views;
    }

    @GetMapping("/api/v1/sales/staff/{userId}/assignment-settings")
    @RequiresPermission(resource = CustomerResources.SALES_WORKLOAD, action = Action.READ)
    public ApiResponse<SalesAssignmentSettingsService.SettingView> assignmentSettings(
            @PathVariable UUID userId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(settings.get(userId));
    }

    @PutMapping("/api/v1/sales/staff/{userId}/assignment-settings")
    @RequiresPermission(resource = CustomerResources.SALES_OWNERS, action = Action.UPDATE)
    public ApiResponse<SalesAssignmentSettingsService.SettingView> saveAssignmentSettings(
            @PathVariable UUID userId, @AuthenticatedUser CurrentUser user,
            @Valid @RequestBody SalesOwnerRequests.AssignmentSettings request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(settings.save(userId, request.paused(), request.capacityPerMonth(), request.version(),
                user.userId()));
    }

    /**
     * BO-2: customers and open leads of the account about to be locked. {@code openConversations} stays null (the chat
     * counts them, and customer may not depend on chat): the screen reads {@code GET /chat/agents/workload}.
     */
    @GetMapping("/api/v1/identity/users/{userId}/handover-summary")
    @RequiresPermission(resource = IDENTITY_USERS, action = Action.READ)
    public ApiResponse<SalesOwnerViews.HandoverSummary> handoverSummary(@PathVariable UUID userId,
                                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(views.handover(userId));
    }
}

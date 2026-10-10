package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.identity.api.CreateStaffUserCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.UpdateUserCommand;
import com.stockflow.identity.api.UserStatusChange;
import com.stockflow.identity.internal.controller.dto.AssignWarehousesRequest;
import com.stockflow.identity.internal.controller.dto.CreateUserRequest;
import com.stockflow.identity.internal.controller.dto.CreatedUserResponse;
import com.stockflow.identity.internal.controller.dto.PasswordResetRequest;
import com.stockflow.identity.internal.controller.dto.PasswordResetResponse;
import com.stockflow.identity.internal.controller.dto.UpdateUserRequest;
import com.stockflow.identity.internal.controller.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Staff accounts from the back office (SCRUM-454): until this, the only way to create one was
 * {@code deploy/scripts/create-user.sh}.
 *
 * <p>The guards only decide who reaches the endpoint. Which accounts a caller may then touch is the
 * service's rule (SCRUM-456): never their own, and never one holding a permission they lack — so an
 * e-commerce admin can manage sales staff but cannot reset a system admin's password. Customer
 * accounts are listed on request but managed from the customer screens.</p>
 *
 * <p>Role assignment stays at {@code /users/{userId}/roles} in {@link IdentityController}.</p>
 */
@RestController
@RequestMapping("/api/v1/identity/users")
@Tag(name = "User management", description = "Staff accounts: create, edit, lock, disable, reset password")
class UserManagementController {

    private final IdentityService identityService;
    private final UserManagementWebMapper mapper;

    UserManagementController(IdentityService identityService, UserManagementWebMapper mapper) {
        this.identityService = identityService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List staff accounts: search, status, role; sort=field,dir")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.READ)
    public ApiResponse<PageResponse<UserResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "false") boolean includeCustomers,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(identityService.listUsers(new ListUsersQuery(q, status, role, includeCustomers,
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)).map(mapper::toResponse));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "One account with its roles")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.READ)
    public ApiResponse<UserResponse> get(@PathVariable UUID userId) {
        return ApiResponse.ok(mapper.toResponse(identityService.user(userId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a staff account; without a password one is generated and returned once")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.CREATE)
    public ApiResponse<CreatedUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.createStaffUser(new CreateStaffUserCommand(
                request.username(), request.email(), request.fullName(), request.password(), request.roles(),
                request.warehouseIds()))));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Edit e-mail or full name; send the version you loaded")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> update(@PathVariable UUID userId,
                                            @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.updateUser(
                new UpdateUserCommand(userId, request.version(), request.email(), request.fullName()))));
    }

    /** Replace the warehouses the account works in (SCRUM-457); applies on the user's next request. */
    @PutMapping("/{userId}/warehouses")
    @Operation(summary = "Set the warehouses a warehouse-bound account may act on")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> assignWarehouses(@PathVariable UUID userId,
                                                      @Valid @RequestBody AssignWarehousesRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.assignWarehouses(userId, request.warehouseIds())));
    }

    @PostMapping("/{userId}/lock")
    @Operation(summary = "Lock the account and sign it out everywhere")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> lock(@PathVariable UUID userId) {
        return status(userId, UserStatusChange.LOCK);
    }

    @PostMapping("/{userId}/unlock")
    @Operation(summary = "Unlock the account, also ending a lock after too many wrong passwords")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> unlock(@PathVariable UUID userId) {
        return status(userId, UserStatusChange.UNLOCK);
    }

    @PostMapping("/{userId}/disable")
    @Operation(summary = "Retire the account (kept for its history) and sign it out everywhere")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> disable(@PathVariable UUID userId) {
        return status(userId, UserStatusChange.DISABLE);
    }

    @PostMapping("/{userId}/enable")
    @Operation(summary = "Bring a disabled account back")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<UserResponse> enable(@PathVariable UUID userId) {
        return status(userId, UserStatusChange.ENABLE);
    }

    @PostMapping("/{userId}/password-reset")
    @Operation(summary = "Set a temporary password (generated if none is given) and sign the account out")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<PasswordResetResponse> resetPassword(@PathVariable UUID userId,
                                                            @Valid @RequestBody(required = false)
                                                            PasswordResetRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.resetPassword(userId,
                request == null ? null : request.newPassword())));
    }

    private ApiResponse<UserResponse> status(UUID userId, UserStatusChange change) {
        return ApiResponse.ok(mapper.toResponse(identityService.changeUserStatus(userId, change)));
    }
}

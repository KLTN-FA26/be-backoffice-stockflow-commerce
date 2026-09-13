package com.stockflow.identity.internal.controller;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.internal.controller.dto.AssignRoleRequest;
import com.stockflow.identity.internal.controller.dto.RoleResponse;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.RoleMatrixView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for identity: roles, the permission matrix, and role assignment.
 *
 * <p>No JPA entity crosses this boundary. {@code identity.api.RoleSummary} is mapped to
 * {@link RoleResponse} through {@link IdentityWebMapper}, same as {@code InventoryController}
 * maps {@code StockAvailability} to {@code StockItemResponse} — the api type is the cross-module
 * Java contract, this response type is the HTTP wire format, and the two are kept separate on
 * purpose.</p>
 *
 * <p>{@link RoleMatrixView} is the one exception, returned as-is: unlike {@code RoleSummary}, it
 * is not a general-purpose cross-module type that happens to be reused here — its own javadoc
 * states it exists specifically to be "exactly what the permission admin screen needs to render
 * one role, in one response." It is already the wire format by design, not by coincidence, so a
 * wrapper record here would duplicate its nested {@code Group}/{@code Resource}/{@code ActionChip}
 * shape for no behavioural difference.</p>
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} is applied by a Spring AOP
 * proxy; a non-public handler is silently left unguarded (CLAUDE.md's "Spring proxying" trap
 * list) — this module underpins the platform's security, so this matters here more than most.</p>
 */
@RestController
@RequestMapping("/api/v1/identity")
@Tag(name = "Identity", description = "Roles, the permission matrix, and role assignment")
@PermissionResource(
        code = IdentityResources.ROLES,
        group = "Platform",
        label = "Roles",
        route = "/admin/roles",
        apiPath = "/api/v1/identity/roles",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class IdentityController {

    private final IdentityService identityService;
    private final IdentityWebMapper mapper;

    IdentityController(IdentityService identityService, IdentityWebMapper mapper) {
        this.identityService = identityService;
        this.mapper = mapper;
    }

    @GetMapping("/roles")
    @Operation(summary = "List the platform's roles")
    @RequiresPermission(resource = IdentityResources.ROLES, action = Action.READ)
    public ApiResponse<List<RoleResponse>> roles() {
        return ApiResponse.ok(mapper.toResponses(identityService.listRoles()));
    }

    @GetMapping("/roles/{roleCode}/permissions")
    @Operation(summary = "Permission matrix for one role, for the permission-management screen")
    @RequiresPermission(resource = IdentityResources.RBAC, action = Action.READ)
    public ApiResponse<RoleMatrixView> permissionsOf(@PathVariable String roleCode) {
        return ApiResponse.ok(identityService.roleMatrix(roleCode));
    }

    /**
     * Assign a role to a user.
     *
     * <p>Guarded on the {@code users} resource, not {@code roles}: this changes what a user may
     * do, which is an administrative action over the user account, not an edit to the role
     * definition itself.</p>
     */
    @PostMapping("/users/{userId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a role to a user")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<Void> assignRole(@PathVariable UUID userId,
                                        @Valid @RequestBody AssignRoleRequest request) {
        identityService.assignRole(userId, request.roleCode());
        return ApiResponse.ok();
    }

    @DeleteMapping("/users/{userId}/roles/{roleCode}")
    @Operation(summary = "Revoke a role from a user")
    @RequiresPermission(resource = IdentityResources.USERS, action = Action.UPDATE)
    public ApiResponse<Void> revokeRole(@PathVariable UUID userId, @PathVariable String roleCode) {
        identityService.revokeRole(userId, roleCode);
        return ApiResponse.ok();
    }
}

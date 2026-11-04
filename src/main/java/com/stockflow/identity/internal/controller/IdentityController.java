package com.stockflow.identity.internal.controller;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.CreateRoleCommand;
import com.stockflow.identity.api.UpdateRoleCommand;
import com.stockflow.identity.internal.controller.dto.AssignRoleRequest;
import com.stockflow.identity.internal.controller.dto.CreateRoleRequest;
import com.stockflow.identity.internal.controller.dto.UpdateRoleRequest;
import com.stockflow.identity.internal.controller.dto.RoleResponse;
import com.stockflow.identity.internal.controller.dto.UpdateRolePermissionsRequest;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
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

    /**
     * Add a custom role (SCRUM-455). It grants nothing until permissions are ticked on, unless it
     * starts as a copy of another role — which the caller may only copy if they hold everything it
     * grants.
     */
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a custom role, empty or copying another role's permissions")
    @RequiresPermission(resource = IdentityResources.ROLES, action = Action.CREATE)
    public ApiResponse<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.createRole(new CreateRoleCommand(
                request.code(), request.name(), request.description(), request.copyPermissionsFrom(),
                request.dataScope()))));
    }

    @PatchMapping("/roles/{roleCode}")
    @Operation(summary = "Rename a custom role or change its data scope; system roles cannot be changed")
    @RequiresPermission(resource = IdentityResources.ROLES, action = Action.UPDATE)
    public ApiResponse<RoleResponse> updateRole(@PathVariable String roleCode,
                                                @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(mapper.toResponse(identityService.updateRole(new UpdateRoleCommand(
                roleCode, request.version(), request.name(), request.description(), request.dataScope()))));
    }

    @DeleteMapping("/roles/{roleCode}")
    @Operation(summary = "Delete a custom role nobody holds any more")
    @RequiresPermission(resource = IdentityResources.ROLES, action = Action.DELETE)
    public ApiResponse<Void> deleteRole(@PathVariable String roleCode) {
        identityService.deleteRole(roleCode);
        return ApiResponse.ok();
    }

    @GetMapping("/roles/{roleCode}/permissions")
    @Operation(summary = "Permission matrix for one role, for the permission-management screen")
    @RequiresPermission(resource = IdentityResources.RBAC, action = Action.READ)
    public ApiResponse<RoleMatrixView> permissionsOf(@PathVariable String roleCode) {
        return ApiResponse.ok(identityService.roleMatrix(roleCode));
    }

    /**
     * Replace everything a role grants — the save button of the permission-management screen.
     *
     * <p>Guarded on {@code identity-rbac:APPROVE}, not a plain UPDATE: deciding who may do what is
     * the most sensitive action in the system, and APPROVE is one of the actions "select all" never
     * hands out. Holders of the role see the change on their next request (ADR-0008).</p>
     */
    @PutMapping("/roles/{roleCode}/permissions")
    @Operation(summary = "Replace the permissions a role grants; send the version you loaded")
    @RequiresPermission(resource = IdentityResources.RBAC, action = Action.APPROVE)
    public ApiResponse<RoleMatrixView> updatePermissions(@PathVariable String roleCode,
                                                         @Valid @RequestBody UpdateRolePermissionsRequest request) {
        return ApiResponse.ok(identityService.updateRolePermissions(
                new UpdateRolePermissionsCommand(roleCode, request.version(), request.permissions())));
    }

    /** Tick one permission on — the caller must hold it themselves. */
    @PostMapping("/roles/{roleCode}/permissions/{permission}")
    @Operation(summary = "Grant one permission (resource:ACTION) to a role")
    @RequiresPermission(resource = IdentityResources.RBAC, action = Action.APPROVE)
    public ApiResponse<RoleMatrixView> grantPermission(@PathVariable String roleCode,
                                                       @PathVariable String permission) {
        return ApiResponse.ok(identityService.grantPermission(roleCode, permission));
    }

    @DeleteMapping("/roles/{roleCode}/permissions/{permission}")
    @Operation(summary = "Take one permission (resource:ACTION) away from a role")
    @RequiresPermission(resource = IdentityResources.RBAC, action = Action.APPROVE)
    public ApiResponse<RoleMatrixView> revokePermission(@PathVariable String roleCode,
                                                        @PathVariable String permission) {
        return ApiResponse.ok(identityService.revokePermission(roleCode, permission));
    }

    /**
     * Assign a role to a user.
     *
     * <p>Guarded on the {@code users} resource, not {@code roles}: this changes what a user may
     * do, which is an administrative action over the user account, not an edit to the role
     * definition itself. The guard only lets the caller in; the service then refuses a role that
     * grants more than the caller holds (SCRUM-456).</p>
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

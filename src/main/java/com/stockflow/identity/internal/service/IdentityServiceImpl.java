package com.stockflow.identity.internal.service;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.RoleSummary;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.identity.internal.entity.RolePermissionJpaEntity;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.repository.PermissionJpaRepository;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.RolePermissionJpaRepository;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RoleMatrixAssembler;
import com.stockflow.common.security.RoleMatrixView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only implementation of {@link IdentityService}, and the module's transaction boundary.
 *
 * <p>No {@code User} aggregate is loaded here. Assigning or revoking a role is a join-row
 * operation whose only invariant — a user cannot hold the same role twice — is already enforced by
 * {@code uk_user_role} in the database; there is no state machine or cross-field rule that would
 * justify routing it through {@code User.java}'s (still-stub) aggregate. That aggregate is for a
 * later ticket that gives the user account itself real behaviour (activation, lockout, etc).</p>
 *
 * <p><b>Also no repository port here, unlike {@code inventory}'s
 * {@code StockItemRepository}/{@code StockItemRepositoryAdapter} split.</b> That split earns its
 * keep when the domain has behaviour to protect from persistence concerns (FEFO planning,
 * pessimistic locking, entity↔aggregate mapping). Role/permission/user-role data has none of that
 * — it is flat reference data — so the five {@code *JpaRepository} interfaces are {@code public}
 * and injected directly, rather than adding a port/adapter/mapper trio that would map one flat
 * shape onto another for no behavioural gain. If this module later grows real domain logic (a
 * user activation/lockout state machine, say), that is the point to introduce the port for
 * whichever aggregate carries it — not before.</p>
 */
@Service
@Transactional
class IdentityServiceImpl implements IdentityService {

    private final RoleJpaRepository roles;
    private final PermissionJpaRepository permissions;
    private final RolePermissionJpaRepository rolePermissions;
    private final UserRoleJpaRepository userRoles;
    private final UserJpaRepository users;
    private final PermissionCatalog catalog;

    IdentityServiceImpl(RoleJpaRepository roles, PermissionJpaRepository permissions,
                        RolePermissionJpaRepository rolePermissions, UserRoleJpaRepository userRoles,
                        UserJpaRepository users, PermissionCatalog catalog) {
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.userRoles = userRoles;
        this.users = users;
        this.catalog = catalog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleSummary> listRoles() {
        return roles.findAllByOrderByCodeAsc().stream()
                .map(r -> new RoleSummary(r.getCode(), r.getName(), r.getDescription()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code dataScope} is passed as {@link DataScope#ALL} for every role: {@code app_role} has
     * no scope column yet (ADR-0004 records the row-visibility filter as the largest unimplemented
     * part of the permission model), so this is a placeholder for the matrix screen to render, not
     * a real grant. {@code systemRole} is {@code true} for all ten seeded roles — none of them is
     * yet editable through this API.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public RoleMatrixView roleMatrix(String roleCode) {
        RoleJpaEntity role = findRoleByCode(roleCode);
        Set<PermissionCode> granted = grantedPermissionsOf(role.getId());
        return RoleMatrixAssembler.assemble(
                role.getCode(), role.getName(), true, DataScope.ALL, catalog, granted);
    }

    @Override
    public void assignRole(UUID userId, String roleCode) {
        requireUser(userId);
        RoleJpaEntity role = findRoleByCode(roleCode);
        if (userRoles.findByUserIdAndRoleId(userId, role.getId()).isPresent()) {
            return;
        }
        userRoles.save(new UserRoleJpaEntity(Identifiers.newId(), userId, role.getId()));
    }

    @Override
    public void revokeRole(UUID userId, String roleCode) {
        requireUser(userId);
        RoleJpaEntity role = findRoleByCode(roleCode);
        userRoles.findByUserIdAndRoleId(userId, role.getId()).ifPresent(userRoles::delete);
    }

    private void requireUser(UUID userId) {
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "No user with id " + userId);
        }
    }

    private RoleJpaEntity findRoleByCode(String code) {
        return roles.findByCode(code)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ROLE_NOT_FOUND, "No role with code " + code));
    }

    /**
     * {@code role_permission} stores a plain {@code permission_id} column rather than a JPA
     * {@code @ManyToOne} — consistent with this codebase's convention of plain UUID reference
     * columns for a same-schema join, not just cross-module ones — so this is two lookups rather
     * than one navigable relationship.
     */
    private Set<PermissionCode> grantedPermissionsOf(UUID roleId) {
        List<UUID> permissionIds = rolePermissions.findByRoleId(roleId).stream()
                .map(RolePermissionJpaEntity::getPermissionId)
                .toList();
        return permissions.findAllById(permissionIds).stream()
                .map(p -> PermissionCode.of(p.getResource(), Action.valueOf(p.getAction())))
                .collect(Collectors.toUnmodifiableSet());
    }
}

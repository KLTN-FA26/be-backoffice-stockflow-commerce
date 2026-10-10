package com.stockflow.identity.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.identity.api.ChangePasswordCommand;
import com.stockflow.identity.api.CreateRoleCommand;
import com.stockflow.identity.api.CreateStaffUserCommand;
import com.stockflow.identity.api.CreatedStaffUser;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.PasswordReset;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.identity.api.UpdateRoleCommand;
import com.stockflow.identity.api.UpdateUserCommand;
import com.stockflow.identity.api.UserStatusChange;
import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.Roles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SessionSummary;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.identity.api.RoleSummary;
import com.stockflow.identity.api.TokenResponse;
import com.stockflow.identity.api.RegisterAccountCommand;
import com.stockflow.identity.api.RegisteredAccount;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.identity.api.UserProfile;
import com.stockflow.identity.internal.domain.PasswordPolicy;
import com.stockflow.identity.internal.domain.SessionEndReason;
import com.stockflow.identity.internal.domain.User;
import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.domain.UserRepository;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.identity.internal.entity.RolePermissionJpaEntity;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.entity.UserSessionJpaEntity;
import com.stockflow.identity.internal.repository.PermissionJpaRepository;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.RolePermissionJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import com.stockflow.identity.internal.repository.UserSessionJpaRepository;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RoleMatrixAssembler;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.common.security.SessionValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.AuditorAware;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * The only implementation of {@link IdentityService}, and the module's transaction boundary.
 *
 * <p>Role/permission assignment stays a join-row operation with no {@code User} aggregate involved
 * (see the reasoning that used to be the whole point of this javadoc, still true for
 * {@link #assignRole}/{@link #revokeRole}). {@link #login} (SCRUM-378) is the exception: signing in
 * has a real invariant to protect — a {@code LOCKED}/{@code DISABLED} account must never
 * authenticate — so it is the one operation here that goes through {@link UserRepository} and the
 * {@code User} aggregate instead of a raw JPA repository.</p>
 */
@Service
@Transactional
class IdentityServiceImpl implements IdentityService {

    private static final Duration MAX_TOKEN_TTL = Duration.ofHours(24);

    /** The right to edit the matrix itself. Some role must always keep it. */
    private static final PermissionCode MANAGE_PERMISSIONS = PermissionCode.of("identity-rbac", Action.APPROVE);

    /** Key of the advisory lock every matrix edit takes; any constant unique to this purpose. */
    private static final long MATRIX_EDIT_LOCK = 0x5ecb_acL;

    private final RoleJpaRepository roles;
    private final PermissionJpaRepository permissions;
    private final RolePermissionJpaRepository rolePermissions;
    private final UserRoleJpaRepository userRoles;
    private final UserRepository userRepository;
    private final PermissionCatalog catalog;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final UserSessionJpaRepository sessions;
    private final RoleAuthorizationCache authorizationCache;
    private final AuditorAware<String> auditor;
    private final UserJpaRepository users;
    private final PrivilegeGuard guard;
    private final LoginAttempts loginAttempts;

    private static final SortWhitelist USER_SORT =
            SortWhitelist.of("username", "fullName", "status", "lastLoginAt", "createdAt")
                    .withDefault("username", Sort.Direction.ASC);

    /**
     * How long a token, and its session, lives. It was a fixed hour because nothing could end a token
     * early; now that a session can be revoked (logout, password change, role change) the lifetime
     * only bounds how long an abandoned or stolen device keeps working, so it can be a working day.
     * Configured by {@code stockflow.security.token-ttl}.
     */
    private final Duration tokenTtl;

    /** Hashed once at startup and matched against for an unknown username, so a login attempt for
     *  a real account and a made-up one cost the same bcrypt work — the timing side-channel that
     *  would otherwise let an attacker enumerate valid usernames. */
    private final String dummyPasswordHash;

    IdentityServiceImpl(RoleJpaRepository roles, PermissionJpaRepository permissions,
                        RolePermissionJpaRepository rolePermissions, UserRoleJpaRepository userRoles,
                        UserRepository userRepository, PermissionCatalog catalog,
                        PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, Clock clock,
                        UserSessionJpaRepository sessions, RoleAuthorizationCache authorizationCache,
                        AuditorAware<String> auditor, UserJpaRepository users, PrivilegeGuard guard,
                        LoginAttempts loginAttempts,
                        @Value("${stockflow.security.token-ttl:PT8H}") Duration tokenTtl) {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()
                || tokenTtl.compareTo(MAX_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "stockflow.security.token-ttl must be positive and at most 24h, got " + tokenTtl);
        }
        this.roles = roles;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.userRoles = userRoles;
        this.userRepository = userRepository;
        this.catalog = catalog;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.sessions = sessions;
        this.authorizationCache = authorizationCache;
        this.auditor = auditor;
        this.users = users;
        this.guard = guard;
        this.loginAttempts = loginAttempts;
        this.tokenTtl = tokenTtl;
        this.dummyPasswordHash = passwordEncoder.encode(Identifiers.newId().toString());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleSummary> listRoles() {
        Map<UUID, Long> holders = new java.util.HashMap<>();
        roles.countHoldersPerRole().forEach(row -> holders.put((UUID) row[0], ((Number) row[1]).longValue()));
        return roles.findAllByOrderByCodeAsc().stream()
                .map(r -> summaryOf(r, holders.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    private static RoleSummary summaryOf(RoleJpaEntity r, long holderCount) {
        return new RoleSummary(r.getCode(), r.getName(), r.getDescription(), r.isSystem(), r.getVersion(),
                holderCount, r.getCreatedAt(), r.getCreatedBy(), r.getLastModifiedAt(), r.getLastModifiedBy());
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code dataScope} is passed as {@link DataScope#ALL} for every role: {@code app_role} has
     * no scope column yet (ADR-0004 records the row-visibility filter as the largest unimplemented
     * part of the permission model), so this is a placeholder for the matrix screen to render, not
     * a real grant. {@code systemRole} is {@code true} for every seeded role: they cannot be
     * renamed or deleted, but their grants are editable through
     * {@link #updateRolePermissions} — all except {@code CUSTOMER}'s and {@code SYSTEM_ADMIN}'s, see
     * {@link #isEditable}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public RoleMatrixView roleMatrix(String roleCode) {
        RoleJpaEntity role = findRoleByCode(roleCode);
        return matrixOf(role, role.getVersion(), grantedPermissionsOf(role.getId()));
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Order of operations</h2>
     *
     * <ol>
     *   <li>Every code is checked against the catalog and the {@code permission} table before
     *       anything is written, so a typo never half-applies.</li>
     *   <li>All matrix edits take one transaction-scoped advisory lock. Without it two
     *       administrators each removing the RBAC right from a different role would both see the
     *       other role still holding it, and both commit — leaving nobody able to undo either.</li>
     *   <li>The role's version is advanced with a compare-and-set; zero rows means someone saved
     *       after this editor loaded, and the request is refused rather than overwriting them.</li>
     *   <li>Only the difference is written, and the cache pointer moves after commit.</li>
     * </ol>
     *
     * <h2>"Replace" means replace what the screen shows</h2>
     *
     * <p>The seed grants permissions for resources no {@code @PermissionResource} declares yet —
     * modules not built. The matrix cannot show them and this method refuses them as input, so an
     * editor can never send them back. Replacing literally everything therefore deleted them on the
     * first save of an unchanged matrix (SALES_STAFF: 49 grants in, 20 out). Only grants the catalog
     * declares are replaced; the rest are left exactly as they are.</p>
     */
    @Override
    @Auditable(action = AuditAction.GRANT, resourceType = "role", resourceId = "#command.roleCode()")
    public RoleMatrixView updateRolePermissions(UpdateRolePermissionsCommand command) {
        RoleJpaEntity role = findRoleByCode(command.roleCode());
        if (!isEditable(role)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_EDITABLE,
                    "The permissions of role " + role.getCode() + " are managed by migrations");
        }
        Map<PermissionCode, UUID> requested = resolvePermissionIds(command.permissions());

        rolePermissions.lockMatrixEdits(MATRIX_EDIT_LOCK);
        if (roles.advanceVersion(role.getId(), command.expectedVersion(), clock.instant(), actor()) == 0) {
            throw new BusinessException(ErrorCode.ROLE_PERMISSIONS_CHANGED,
                    "Role %s is no longer at version %d".formatted(role.getCode(), command.expectedVersion()));
        }

        List<RolePermissionJpaEntity> held = rolePermissions.findByRoleId(role.getId());
        Set<UUID> wanted = new HashSet<>(requested.values());
        refuseLockout(role, held, wanted);

        Set<UUID> onScreen = declaredPermissionIds(held);
        List<RolePermissionJpaEntity> removed = held.stream()
                .filter(row -> onScreen.contains(row.getPermissionId()))
                .filter(row -> !wanted.contains(row.getPermissionId()))
                .toList();
        Set<UUID> kept = held.stream().map(RolePermissionJpaEntity::getPermissionId)
                .collect(Collectors.toSet());
        guard.requireHolds(requested.entrySet().stream()
                .filter(entry -> !kept.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList());
        List<RolePermissionJpaEntity> added = wanted.stream()
                .filter(permissionId -> !kept.contains(permissionId))
                .map(permissionId -> new RolePermissionJpaEntity(Identifiers.newId(), role.getId(), permissionId))
                .toList();
        rolePermissions.deleteAll(removed);
        rolePermissions.saveAll(added);

        long newVersion = command.expectedVersion() + 1;
        afterCommit(() -> authorizationCache.publishVersion(role.getCode(), newVersion));
        return matrixOf(role, newVersion, requested.keySet());
    }

    /**
     * Two roles are excluded, and their grants stay in migrations, reviewed like code.
     *
     * <ul>
     *   <li>CUSTOMER: its grants are the storefront's self-service surface, and one wrong tick would
     *       hand every shopper a back-office permission.</li>
     *   <li>SYSTEM_ADMIN: it holds every permission by definition. One wrong untick would turn the
     *       role meant to repair the matrix into an ordinary one, possibly unable to repair it.</li>
     * </ul>
     */
    private static boolean isEditable(RoleJpaEntity role) {
        String code = role.getCode();
        return !com.stockflow.common.security.Roles.CUSTOMER.equals(code)
                && !com.stockflow.common.security.Roles.SYSTEM_ADMIN.equals(code);
    }

    /** Parses and checks every code up front: declared by a {@code @PermissionResource}, and seeded. */
    private Map<PermissionCode, UUID> resolvePermissionIds(List<String> rawCodes) {
        Set<String> unknown = new TreeSet<>();
        Set<PermissionCode> codes = new LinkedHashSet<>();
        for (String raw : rawCodes) {
            try {
                PermissionCode code = PermissionCode.parse(raw);
                if (catalog.isDeclared(code)) {
                    codes.add(code);
                } else {
                    unknown.add(raw);
                }
            } catch (IllegalArgumentException malformed) {
                unknown.add(String.valueOf(raw));
            }
        }
        Map<String, UUID> seeded = permissions.findByCodeIn(codes.stream().map(PermissionCode::toString).toList())
                .stream()
                .collect(Collectors.toMap(p -> p.getCode(), p -> p.getId()));
        Map<PermissionCode, UUID> ids = new LinkedHashMap<>();
        for (PermissionCode code : codes) {
            UUID id = seeded.get(code.toString());
            if (id == null) {
                // Declared in code but never inserted into identity.permission: a missing migration,
                // not something an administrator can fix by retrying.
                unknown.add(code.toString());
            } else {
                ids.put(code, id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.UNKNOWN_PERMISSION, "Unknown permissions: " + unknown);
        }
        return ids;
    }

    /** Of the grants held, the ones the catalog declares — the only ones the matrix shows and edits. */
    private Set<UUID> declaredPermissionIds(List<RolePermissionJpaEntity> held) {
        List<UUID> ids = held.stream().map(RolePermissionJpaEntity::getPermissionId).toList();
        Set<UUID> declared = new HashSet<>();
        for (var permission : permissions.findAllById(ids)) {
            try {
                if (catalog.isDeclared(PermissionCode.parse(permission.getCode()))) {
                    declared.add(permission.getId());
                }
            } catch (IllegalArgumentException notAPermissionCode) {
                // Not editable here either: keep it.
            }
        }
        return declared;
    }

    /** Refuses a change that would leave no role able to manage permissions. */
    private void refuseLockout(RoleJpaEntity role, List<RolePermissionJpaEntity> held, Set<UUID> wanted) {
        permissions.findByCodeIn(List.of(MANAGE_PERMISSIONS.toString())).stream().findFirst()
                .ifPresent(manage -> {
                    boolean holdsNow = held.stream().anyMatch(row -> row.getPermissionId().equals(manage.getId()));
                    boolean keeps = wanted.contains(manage.getId());
                    if (holdsNow && !keeps
                            && !rolePermissions.existsByPermissionIdAndRoleIdNot(manage.getId(), role.getId())) {
                        throw new BusinessException(ErrorCode.RBAC_LOCKOUT, "Role " + role.getCode()
                                + " is the last one holding " + MANAGE_PERMISSIONS);
                    }
                });
    }

    private RoleMatrixView matrixOf(RoleJpaEntity role, long version, Set<PermissionCode> granted) {
        return RoleMatrixAssembler.assemble(role.getCode(), role.getName(), role.isSystem(), isEditable(role),
                version, DataScope.ALL, catalog, granted);
    }

    private String actor() {
        return auditor.getCurrentAuditor().orElse("system");
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Order matters for what the caller learns: existence first (404), then whether this role can
     * go on this account at all (409), then whether the caller may do it (403).</p>
     */
    @Override
    @Auditable(action = AuditAction.GRANT, resourceType = "user", resourceId = "#userId")
    public void assignRole(UUID userId, String roleCode) {
        UserJpaEntity account = requireAccount(userId);
        RoleJpaEntity role = findRoleByCode(roleCode);
        List<String> held = codesOf(rolesOf(new UserId(userId)));
        if (Roles.CUSTOMER.equals(role.getCode()) || held.contains(Roles.CUSTOMER)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE,
                    "Role %s cannot be assigned to account %s".formatted(role.getCode(), account.getUsername()));
        }
        guard.requireCanManage(userId, held);
        guard.requireHoldsRoles(List.of(role.getCode()));
        if (userRoles.findByUserIdAndRoleId(userId, role.getId()).isPresent()) {
            return;
        }
        userRoles.save(new UserRoleJpaEntity(Identifiers.newId(), userId, role.getId()));
        endSessionsAfterRoleChange(userId);
    }

    @Override
    @Auditable(action = AuditAction.REVOKE, resourceType = "user", resourceId = "#userId")
    public void revokeRole(UUID userId, String roleCode) {
        UserJpaEntity account = requireAccount(userId);
        RoleJpaEntity role = findRoleByCode(roleCode);
        if (Roles.CUSTOMER.equals(role.getCode())) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE,
                    "Role CUSTOMER is not revoked from account " + account.getUsername());
        }
        guard.requireCanManage(userId, codesOf(rolesOf(new UserId(userId))));
        Optional<UserRoleJpaEntity> held = userRoles.findByUserIdAndRoleId(userId, role.getId());
        if (held.isEmpty()) {
            return;
        }
        if (Roles.SYSTEM_ADMIN.equals(role.getCode()) && account.getStatus() == UserStatus.ACTIVE) {
            refuseLastSystemAdmin(userId);
        }
        userRoles.delete(held.get());
        endSessionsAfterRoleChange(userId);
    }

    /** Refuses to leave the platform without an active SYSTEM_ADMIN, if {@code userId} is one now. */
    private void refuseLastSystemAdmin(UUID userId) {
        roles.findByCode(Roles.SYSTEM_ADMIN).ifPresent(admin -> {
            if (userRoles.findByUserIdAndRoleId(userId, admin.getId()).isPresent()
                    && userRoles.countOtherActiveHolders(admin.getId(), userId) == 0) {
                throw new BusinessException(ErrorCode.LAST_SYSTEM_ADMIN,
                        "User %s is the last active System Admin".formatted(userId));
            }
        });
    }

    /** A token names the roles held at sign-in, so it must not outlive a change to them. Permission
     *  changes need no such step: they are resolved per request (ADR-0008). */
    private void endSessionsAfterRoleChange(UUID userId) {
        sessions.revokeLive(userId, null, clock.instant(), SessionEndReason.ROLE_CHANGED);
    }

    // ---- single-permission edits and custom roles (SCRUM-455) -------------------------------

    @Override
    @Auditable(action = AuditAction.GRANT, resourceType = "role", resourceId = "#roleCode")
    public RoleMatrixView grantPermission(String roleCode, String permission) {
        return editOne(roleCode, permission, true);
    }

    @Override
    @Auditable(action = AuditAction.REVOKE, resourceType = "role", resourceId = "#roleCode")
    public RoleMatrixView revokePermission(String roleCode, String permission) {
        return editOne(roleCode, permission, false);
    }

    /**
     * One tick or untick of the matrix. The same lock, version move and lockout guard as a full
     * save, without asking for the version the editor loaded: adding or removing one permission is
     * idempotent, so there is nothing of someone else's to overwrite.
     */
    private RoleMatrixView editOne(String roleCode, String permission, boolean grant) {
        RoleJpaEntity role = findRoleByCode(roleCode);
        if (!isEditable(role)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_EDITABLE,
                    "The permissions of role " + role.getCode() + " are managed by migrations");
        }
        Map.Entry<PermissionCode, UUID> target =
                resolvePermissionIds(List.of(permission)).entrySet().iterator().next();
        if (grant) {
            guard.requireHolds(List.of(target.getKey()));
        }
        rolePermissions.lockMatrixEdits(MATRIX_EDIT_LOCK);
        Optional<RolePermissionJpaEntity> current =
                rolePermissions.findByRoleIdAndPermissionId(role.getId(), target.getValue());
        if (grant == current.isPresent()) {
            return roleMatrix(role.getCode());
        }
        long version = role.getVersion();
        if (roles.advanceVersion(role.getId(), version, clock.instant(), actor()) == 0) {
            throw new BusinessException(ErrorCode.ROLE_PERMISSIONS_CHANGED,
                    "Role %s changed while it was being edited".formatted(role.getCode()));
        }
        if (grant) {
            rolePermissions.save(new RolePermissionJpaEntity(Identifiers.newId(), role.getId(), target.getValue()));
        } else {
            List<RolePermissionJpaEntity> held = rolePermissions.findByRoleId(role.getId());
            Set<UUID> wanted = held.stream().map(RolePermissionJpaEntity::getPermissionId)
                    .filter(id -> !id.equals(target.getValue()))
                    .collect(Collectors.toSet());
            refuseLockout(role, held, wanted);
            rolePermissions.delete(current.get());
        }
        long newVersion = version + 1;
        afterCommit(() -> authorizationCache.publishVersion(role.getCode(), newVersion));
        return matrixOf(role, newVersion, grantedPermissionsOf(role.getId()));
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "role", resourceId = "#command.code()")
    public RoleSummary createRole(CreateRoleCommand command) {
        String code = command.code().trim().toUpperCase(Locale.ROOT);
        if (roles.existsByCode(code)) {
            throw new BusinessException(ErrorCode.ROLE_CODE_ALREADY_EXISTS, "Role " + code + " already exists");
        }
        Set<UUID> copied = new LinkedHashSet<>();
        if (command.copyPermissionsFrom() != null && !command.copyPermissionsFrom().isBlank()) {
            RoleJpaEntity source = findRoleByCode(command.copyPermissionsFrom().trim());
            guard.requireHolds(grantedPermissionsOf(source.getId()));
            rolePermissions.findByRoleId(source.getId()).forEach(row -> copied.add(row.getPermissionId()));
        }
        RoleJpaEntity role = roles.save(new RoleJpaEntity(Identifiers.newId(), code, command.name().trim(),
                blankToNull(command.description()), false));
        rolePermissions.saveAll(copied.stream()
                .map(permissionId -> new RolePermissionJpaEntity(Identifiers.newId(), role.getId(), permissionId))
                .toList());
        return summaryOf(role, 0);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "role", resourceId = "#command.code()")
    public RoleSummary updateRole(UpdateRoleCommand command) {
        RoleJpaEntity role = findRoleByCode(command.code());
        requireCustom(role);
        if (role.getVersion() != command.expectedVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Role %s is no longer at version %d".formatted(role.getCode(), command.expectedVersion()));
        }
        role.rename(command.name().trim(), blankToNull(command.description()));
        RoleJpaEntity saved = roles.saveAndFlush(role);
        // The rename moved the row's version, which is also the key of its cached grants.
        long version = saved.getVersion();
        afterCommit(() -> authorizationCache.publishVersion(saved.getCode(), version));
        return summaryOf(saved, userRoles.countByRoleId(saved.getId()));
    }

    @Override
    @Auditable(action = AuditAction.DELETE, resourceType = "role", resourceId = "#roleCode")
    public void deleteRole(String roleCode) {
        RoleJpaEntity role = findRoleByCode(roleCode);
        requireCustom(role);
        long holders = userRoles.countByRoleId(role.getId());
        if (holders > 0) {
            throw new BusinessException(ErrorCode.ROLE_IN_USE,
                    "Role %s is held by %d account(s)".formatted(role.getCode(), holders));
        }
        rolePermissions.lockMatrixEdits(MATRIX_EDIT_LOCK);
        List<RolePermissionJpaEntity> held = rolePermissions.findByRoleId(role.getId());
        refuseLockout(role, held, Set.of());
        rolePermissions.deleteAll(held);
        roles.delete(role);
    }

    private static void requireCustom(RoleJpaEntity role) {
        if (role.isSystem()) {
            throw new BusinessException(ErrorCode.SYSTEM_ROLE_IMMUTABLE, "Role " + role.getCode() + " is a system role");
        }
    }

    // ---- staff user management (SCRUM-454) ------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StaffUser> listUsers(ListUsersQuery query) {
        UserStatus status = parseStatus(query.status());
        UUID roleId = query.roleCode() == null || query.roleCode().isBlank()
                ? null : findRoleByCode(query.roleCode().trim()).getId();
        UUID excluded = query.includeCustomers()
                ? null : roles.findByCode(Roles.CUSTOMER).map(RoleJpaEntity::getId).orElse(null);
        String pattern = query.q() == null || query.q().isBlank()
                ? null : "%" + query.q().trim().toLowerCase(Locale.ROOT) + "%";
        Page<UserJpaEntity> page = users.search(pattern, status, roleId, excluded,
                Pages.of(query.page(), query.size(), USER_SORT.parse(query.sort())));
        Map<UUID, List<String>> rolesByUser = roleCodesOf(page.getContent().stream().map(UserJpaEntity::getId).toList());
        return Pages.toResponse(page, user -> staffUserOf(user, rolesByUser.getOrDefault(user.getId(), List.of())));
    }

    @Override
    @Transactional(readOnly = true)
    public StaffUser user(UUID userId) {
        return staffUserOf(requireAccount(userId), codesOf(rolesOf(new UserId(userId))));
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "user", resourceId = "#result?.user()?.userId()")
    public CreatedStaffUser createStaffUser(CreateStaffUserCommand command) {
        String username = command.username().trim();
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "Username " + username + " is taken");
        }
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessException(ErrorCode.USER_EMAIL_ALREADY_EXISTS, "E-mail " + email + " is taken");
        }
        List<RoleJpaEntity> granted = new LinkedHashSet<>(command.roleCodes()).stream()
                .map(code -> findRoleByCode(code.trim()))
                .toList();
        if (granted.stream().anyMatch(role -> Roles.CUSTOMER.equals(role.getCode()))) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ASSIGNABLE, "A staff account cannot hold CUSTOMER");
        }
        guard.requireHoldsRoles(codesOf(granted));

        String temporary = null;
        String password = command.password();
        if (password == null || password.isBlank()) {
            temporary = TemporaryPasswords.generate();
            password = temporary;
        } else {
            requireStrong(password);
        }
        User saved = userRepository.save(User.createStaff(Identifiers.newId(), username, email,
                passwordEncoder.encode(password), blankToNull(command.fullName()), true));
        UUID userId = saved.id().value();
        userRoles.saveAll(granted.stream()
                .map(role -> new UserRoleJpaEntity(Identifiers.newId(), userId, role.getId()))
                .toList());
        return new CreatedStaffUser(user(userId), temporary);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "user", resourceId = "#command.userId()")
    public StaffUser updateUser(UpdateUserCommand command) {
        UUID userId = command.userId();
        UserJpaEntity account = requireStaffAccount(userId);
        if (account.getVersion() != command.expectedVersion()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "User %s is no longer at version %d".formatted(userId, command.expectedVersion()));
        }
        if (command.email() != null) {
            String email = command.email().trim().toLowerCase(Locale.ROOT);
            users.findByEmailIgnoreCase(email)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new BusinessException(ErrorCode.USER_EMAIL_ALREADY_EXISTS, "E-mail " + email + " is taken");
                    });
        }
        User user = loadUser(userId);
        user.updateProfile(command.email(), command.fullName());
        userRepository.save(user);
        return user(userId);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "user", resourceId = "#userId")
    public StaffUser changeUserStatus(UUID userId, UserStatusChange change) {
        requireStaffAccount(userId);
        User user = loadUser(userId);
        boolean takesAccess = change == UserStatusChange.LOCK || change == UserStatusChange.DISABLE;
        if (takesAccess && user.status() == com.stockflow.identity.internal.domain.UserStatus.ACTIVE) {
            refuseLastSystemAdmin(userId);
        }
        switch (change) {
            case LOCK -> user.lock();
            case UNLOCK -> user.unlock();
            case DISABLE -> user.disable();
            case ENABLE -> user.enable();
        }
        userRepository.save(user);
        if (change == UserStatusChange.LOCK) {
            sessions.revokeLive(userId, null, clock.instant(), SessionEndReason.ACCOUNT_LOCKED);
        } else if (change == UserStatusChange.DISABLE) {
            sessions.revokeLive(userId, null, clock.instant(), SessionEndReason.ACCOUNT_DISABLED);
        }
        return user(userId);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "user", resourceId = "#userId")
    public PasswordReset resetPassword(UUID userId, String newPassword) {
        requireStaffAccount(userId);
        String temporary = null;
        String password = newPassword;
        if (password == null || password.isBlank()) {
            temporary = TemporaryPasswords.generate();
            password = temporary;
        } else {
            requireStrong(password);
        }
        User user = loadUser(userId);
        user.resetPassword(passwordEncoder.encode(password));
        userRepository.save(user);
        int ended = sessions.revokeLive(userId, null, clock.instant(), SessionEndReason.PASSWORD_RESET);
        return new PasswordReset(temporary, ended);
    }

    /**
     * The account, if it is a staff account the caller may manage. Customer accounts are left to
     * the customer screens: their sign-in name is their e-mail, and a staff tool editing one would
     * break their login.
     */
    private UserJpaEntity requireStaffAccount(UUID userId) {
        UserJpaEntity account = requireAccount(userId);
        List<String> held = codesOf(rolesOf(new UserId(userId)));
        if (held.contains(Roles.CUSTOMER)) {
            throw new BusinessException(ErrorCode.STAFF_ACCOUNT_REQUIRED,
                    "Account %s is a customer account".formatted(account.getUsername()));
        }
        guard.requireCanManage(userId, held);
        return account;
    }

    private UserJpaEntity requireAccount(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "No user with id " + userId));
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(new UserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "No user with id " + userId));
    }

    private static void requireStrong(String password) {
        PasswordPolicy.violation(password).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        });
    }

    private static UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "status must be one of " + java.util.Arrays.toString(UserStatus.values()));
        }
    }

    private Map<UUID, List<String>> roleCodesOf(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRoleJpaEntity> rows = userRoles.findByUserIdIn(userIds);
        Map<UUID, String> codeById = new java.util.HashMap<>();
        roles.findAllById(rows.stream().map(UserRoleJpaEntity::getRoleId).distinct().toList())
                .forEach(role -> codeById.put(role.getId(), role.getCode()));
        Map<UUID, List<String>> byUser = new java.util.HashMap<>();
        rows.forEach(row -> byUser.computeIfAbsent(row.getUserId(), id -> new java.util.ArrayList<>())
                .add(codeById.get(row.getRoleId())));
        byUser.values().forEach(codes -> codes.sort(null));
        return byUser;
    }

    private static List<String> codesOf(List<RoleJpaEntity> held) {
        return held.stream().map(RoleJpaEntity::getCode).sorted().toList();
    }

    private static StaffUser staffUserOf(UserJpaEntity user, List<String> roleCodes) {
        return new StaffUser(user.getId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.getStatus().name(), roleCodes, user.isMustChangePassword(), user.getLockedUntil(),
                user.getLastLoginAt(), user.getCreatedAt(), user.getCreatedBy(), user.getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        User user = userRepository.findById(new UserId(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "No user with id " + userId));
        return new UserProfile(user.id().value(), user.username(), user.email(), user.fullName(),
                user.status().name(), user.lastLoginAt(), user.mustChangePassword());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveUserWithAnyRole(UUID userId, String... roleCodes) {
        if (userId == null || roleCodes == null || roleCodes.length == 0) {
            return false;
        }
        var user = userRepository.findById(new UserId(userId));
        if (user.isEmpty() || user.get().status() != com.stockflow.identity.internal.domain.UserStatus.ACTIVE) {
            return false;
        }
        var accepted = java.util.Set.of(roleCodes);
        return rolesOf(user.get().id()).stream().anyMatch(role -> accepted.contains(role.getCode()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately never distinguishes "no such username" from "wrong password" in either the
     * response or the time it takes to answer — see {@link #dummyPasswordHash}. Once the password
     * is confirmed correct, {@link User#signIn} is what actually enforces the account-status
     * invariant, and its {@link com.stockflow.identity.internal.domain.AccountNotActiveException}
     * is allowed to reveal the specific reason: at that point the caller has already proven they
     * own the account.</p>
     */
    @Override
    @Auditable(action = AuditAction.LOGIN, resourceType = "user", resourceId = "#command.username()")
    public TokenResponse login(LoginCommand command) {
        String loginName = command.username() == null ? "" : command.username().trim();
        Optional<User> found = userRepository.findByUsername(loginName);
        String hashToCheck = found.map(User::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(command.password(), hashToCheck);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
        }
        User user = found.get();
        if (!passwordMatches) {
            // During a lock the count stays where it is (User#recordFailedSignIn), so nobody can keep
            // an account locked for good; and the answer stays the plain one, so a locked account is
            // indistinguishable from a wrong password to someone who does not know the password.
            loginAttempts.recordFailure(user.id());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
        }
        user.signIn(clock.instant());
        User saved = userRepository.save(user);

        return startSession(saved, rolesOf(saved.id()), command.clientAddress(), command.userAgent());
    }

    /**
     * The one place a token is issued. It creates the session the token points at, so no code path
     * can hand out a token that revocation cannot reach.
     */
    private TokenResponse startSession(User user, List<RoleJpaEntity> grantedRoles,
                                       String clientAddress, String userAgent) {
        Instant issuedAt = clock.instant();
        UserSessionJpaEntity session = sessions.save(new UserSessionJpaEntity(Identifiers.newId(),
                user.id().value(), issuedAt, issuedAt.plus(tokenTtl), clientAddress, userAgent));
        return new TokenResponse(issueToken(user, grantedRoles, session),
                "Bearer", tokenTtl.toSeconds());
    }

    @Override
    @Auditable(action = AuditAction.LOGOUT, resourceType = "session", resourceId = "#sessionId")
    public void logout(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.findByIdAndUserId(sessionId, userId)
                .ifPresent(session -> session.revoke(clock.instant(), SessionEndReason.LOGOUT));
    }

    @Override
    @Auditable(action = AuditAction.LOGOUT, resourceType = "user", resourceId = "#userId")
    public int logoutOtherSessions(UUID userId, UUID keepSessionId) {
        return sessions.revokeLive(userId, keepSessionId, clock.instant(), SessionEndReason.LOGOUT_OTHERS);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SessionSummary> listSessions(UUID userId, UUID currentSessionId, int page, int size) {
        return Pages.toResponse(
                sessions.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
                        userId, clock.instant(), Pages.of(page, size)),
                session -> new SessionSummary(session.getId(), session.getIssuedAt(), session.getExpiresAt(),
                        session.getClientAddress(), session.getUserAgent(),
                        session.getId().equals(currentSessionId)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The current password is checked first and on its own, so a wrong one never reveals whether
     * the new one would have passed the policy. Failures are audited like any other write: a run of
     * {@code INVALID_CURRENT_PASSWORD} against one account is what a stolen token being used to take
     * over the account looks like.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "user", resourceId = "#command.userId()")
    public int changePassword(ChangePasswordCommand command) {
        User user = userRepository.findById(new UserId(command.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND,
                        "No user with id " + command.userId()));
        if (user.status() != com.stockflow.identity.internal.domain.UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        if (!passwordEncoder.matches(command.currentPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        PasswordPolicy.violation(command.newPassword()).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        });
        if (passwordEncoder.matches(command.newPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_UNCHANGED);
        }
        user.changePasswordHash(passwordEncoder.encode(command.newPassword()));
        userRepository.save(user);
        return command.logoutOtherDevices()
                ? sessions.revokeLive(command.userId(), command.currentSessionId(), clock.instant(),
                        SessionEndReason.PASSWORD_CHANGED)
                : 0;
    }

    @Override
    public RegisteredAccount registerCustomer(RegisterAccountCommand command) {
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS,
                    "A customer account with this email already exists");
        }
        PasswordPolicy.violation(command.password()).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason);
        });
        User saved = userRepository.save(User.register(Identifiers.newId(), email,
                passwordEncoder.encode(command.password()), command.fullName()));
        // Granted directly, not through assignRole: a brand-new account has no session to end, and
        // assignRole's "roles changed, sign the user out" would only clear the persistence context
        // in the middle of the registration.
        RoleJpaEntity customerRole = findRoleByCode(com.stockflow.common.security.Roles.CUSTOMER);
        userRoles.save(new UserRoleJpaEntity(Identifiers.newId(), saved.id().value(), customerRole.getId()));
        return new RegisteredAccount(saved.id().value(), startSession(saved, rolesOf(saved.id()), null, null));
    }

    /**
     * Authentication and roles only. Permissions and the data scope are resolved on the server on
     * every request (ADR-0008): carrying them made the token 4 KB — more than a browser can send
     * next to a cookie of the same token — and froze them for the token's whole life.
     *
     * <p>{@code roles} stays because it is small and cannot go stale: a role change ends every
     * session of the user ({@link #endSessionsAfterRoleChange}), so no token outlives it.</p>
     */
    private String issueToken(User user, List<RoleJpaEntity> grantedRoles, UserSessionJpaEntity session) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuedAt(session.getIssuedAt())
                .expiresAt(session.getExpiresAt())
                .id(session.getId().toString())
                .claim(SessionValidator.SESSION_CLAIM, session.getId().toString())
                .subject(user.id().value().toString())
                .claim("preferred_username", user.username())
                .claim("roles", grantedRoles.stream().map(RoleJpaEntity::getCode).toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private List<RoleJpaEntity> rolesOf(UserId userId) {
        List<UUID> roleIds = userRoles.findByUserId(userId.value()).stream()
                .map(UserRoleJpaEntity::getRoleId)
                .toList();
        return roles.findAllById(roleIds);
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
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

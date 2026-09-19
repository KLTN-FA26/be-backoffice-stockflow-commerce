package com.stockflow.identity.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.identity.api.ChangePasswordCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SessionSummary;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.identity.api.RoleSummary;
import com.stockflow.identity.api.TokenResponse;
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

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
                        UserSessionJpaRepository sessions,
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
        this.tokenTtl = tokenTtl;
        this.dummyPasswordHash = passwordEncoder.encode(Identifiers.newId().toString());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleSummary> listRoles() {
        return roles.findAllByOrderByCodeAsc().stream()
                .map(r -> new RoleSummary(r.getCode(), r.getName(), r.getDescription(),
                        r.getCreatedAt(), r.getCreatedBy(),
                        r.getLastModifiedAt(), r.getLastModifiedBy()))
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
        endSessionsAfterRoleChange(userId);
    }

    @Override
    public void revokeRole(UUID userId, String roleCode) {
        requireUser(userId);
        RoleJpaEntity role = findRoleByCode(roleCode);
        userRoles.findByUserIdAndRoleId(userId, role.getId()).ifPresent(held -> {
            userRoles.delete(held);
            endSessionsAfterRoleChange(userId);
        });
    }

    /** A token is a snapshot of the roles and permissions at sign-in, so it must not outlive a change. */
    private void endSessionsAfterRoleChange(UUID userId) {
        sessions.revokeLive(userId, null, clock.instant(), SessionEndReason.ROLE_CHANGED);
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
        Optional<User> found = userRepository.findByUsername(command.username());
        String hashToCheck = found.map(User::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(command.password(), hashToCheck);
        if (found.isEmpty() || !passwordMatches) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid username or password");
        }

        User user = found.get();
        user.signIn(clock.instant());
        User saved = userRepository.save(user);

        List<RoleJpaEntity> grantedRoles = rolesOf(saved.id());
        Set<PermissionCode> grantedPermissions = grantedRoles.stream()
                .flatMap(role -> grantedPermissionsOf(role.getId()).stream())
                .collect(Collectors.toUnmodifiableSet());

        Instant issuedAt = clock.instant();
        UserSessionJpaEntity session = sessions.save(new UserSessionJpaEntity(Identifiers.newId(),
                saved.id().value(), issuedAt, issuedAt.plus(tokenTtl),
                command.clientAddress(), command.userAgent()));
        return new TokenResponse(issueToken(saved, grantedRoles, grantedPermissions, session),
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

    private String issueToken(User user, List<RoleJpaEntity> grantedRoles,
                              Set<PermissionCode> grantedPermissions, UserSessionJpaEntity session) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuedAt(session.getIssuedAt())
                .expiresAt(session.getExpiresAt())
                .id(session.getId().toString())
                .claim(SessionValidator.SESSION_CLAIM, session.getId().toString())
                .subject(user.id().value().toString())
                .claim("preferred_username", user.username())
                .claim("roles", grantedRoles.stream().map(RoleJpaEntity::getCode).toList())
                .claim("permissions", grantedPermissions.stream().map(PermissionCode::toString).toList())
                // No per-role scope data exists yet (see roleMatrix()'s javadoc, ADR-0004) - ALL is
                // the same placeholder used there, not a real per-user grant.
                .claim("scope_level", DataScope.ALL.name())
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

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(new UserId(userId))) {
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
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

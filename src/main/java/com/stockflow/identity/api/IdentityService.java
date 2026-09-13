package com.stockflow.identity.api;

import com.stockflow.common.security.RoleMatrixView;

import java.util.List;
import java.util.UUID;

/**
 * THE public API of the identity module — the only package other modules may import.
 *
 * <p>{@link RoleMatrixView} is {@code common.security}, not {@code identity.internal} — reusing it
 * here is deliberate, not a boundary leak: {@code RoleMatrixAssembler}'s own javadoc anticipates
 * exactly this caller ("identity-service collects one of these from every service and assembles
 * the full matrix that the admin screen renders"). {@code ArchitectureTest.theApiPackageLeaksNothingInternal}
 * only forbids depending on {@code ..internal..}; the platform-wide security vocabulary in
 * {@code common} is available to every module's {@code api}, same as it is to every module's
 * {@code internal}.</p>
 */
public interface IdentityService {

    /** The platform's roles, in {@code identity.app_role}. */
    List<RoleSummary> listRoles();

    /** The permission matrix for one role, for the permission-management screen. */
    RoleMatrixView roleMatrix(String roleCode);

    /** Assign a role to a user. Idempotent: assigning an already-held role is a no-op. */
    void assignRole(UUID userId, String roleCode);

    /** Revoke a role from a user. Idempotent: revoking one not held is a no-op. */
    void revokeRole(UUID userId, String roleCode);

    /**
     * Verify credentials and issue a signed staff token (SCRUM-378/WBS 3.19.7).
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNAUTHORIZED} for an unknown
     *         username or a wrong password (never revealing which); {@code ACCOUNT_NOT_ACTIVE} if
     *         the password was correct but the account is {@code LOCKED}/{@code DISABLED}
     */
    TokenResponse login(LoginCommand command);
}

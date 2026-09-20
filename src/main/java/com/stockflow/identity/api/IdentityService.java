package com.stockflow.identity.api;

import com.stockflow.common.security.RoleMatrixView;

import com.stockflow.common.api.PageResponse;
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

    /** Used by task assignment to reject inactive users and users outside an operational role. */
    boolean isActiveUserWithAnyRole(UUID userId, String... roleCodes);

    /**
     * Verify credentials and issue a signed staff token (SCRUM-378/WBS 3.19.7).
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNAUTHORIZED} for an unknown
     *         username or a wrong password (never revealing which); {@code ACCOUNT_NOT_ACTIVE} if
     *         the password was correct but the account is {@code LOCKED}/{@code DISABLED}
     */
    TokenResponse login(LoginCommand command);

    /** Creates an ACTIVE account with the CUSTOMER role and issues its first token atomically. */
    RegisteredAccount registerCustomer(RegisterAccountCommand command);

    /**
     * Ends one session, so its token stops working on the very next request. Idempotent, and a
     * no-op for a session that belongs to someone else or does not exist: a caller can only ever
     * end their own.
     *
     * @param sessionId may be null for a token issued before sessions existed, which cannot be ended
     */
    void logout(UUID userId, UUID sessionId);

    /**
     * Ends every live session of the user except {@code keepSessionId} ("sign out my other
     * devices").
     *
     * @param keepSessionId the session to keep; null ends all of them
     * @return how many sessions were ended
     */
    int logoutOtherSessions(UUID userId, UUID keepSessionId);

    /** The user's live sessions, newest first. */
    PageResponse<SessionSummary> listSessions(UUID userId, UUID currentSessionId, int page, int size);

    /**
     * Changes the password after checking the current one.
     *
     * @return how many other sessions were ended (0 unless {@code logoutOtherDevices})
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_CURRENT_PASSWORD} if the
     *         current password is wrong, {@code PASSWORD_UNCHANGED} if the new one equals it,
     *         {@code VALIDATION_FAILED} if the new one breaks the password policy
     */
    int changePassword(ChangePasswordCommand command);
}

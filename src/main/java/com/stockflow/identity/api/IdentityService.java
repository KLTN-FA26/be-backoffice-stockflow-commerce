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

    /**
     * Replace the permissions a role grants, and return the matrix as saved. Holders of the role see
     * the change on their next request; no one has to sign in again (ADR-0008).
     *
     * @throws com.stockflow.common.error.BusinessException {@code ROLE_NOT_FOUND};
     *         {@code ROLE_NOT_EDITABLE} for a role managed by migrations; {@code UNKNOWN_PERMISSION}
     *         for a code that is not declared or not seeded; {@code ROLE_PERMISSIONS_CHANGED} when
     *         the role moved past {@code expectedVersion}; {@code RBAC_LOCKOUT} when no role would
     *         be left able to manage permissions
     */
    RoleMatrixView updateRolePermissions(UpdateRolePermissionsCommand command);

    /**
     * Grant one permission to a role. Idempotent. Same rules as {@link #updateRolePermissions}, and
     * the caller must hold the permission themselves (SCRUM-456).
     */
    RoleMatrixView grantPermission(String roleCode, String permission);

    /** Take one permission away from a role. Idempotent. Refuses an RBAC lockout. */
    RoleMatrixView revokePermission(String roleCode, String permission);

    /**
     * Create a custom role (SCRUM-455), empty or with the grants of {@code copyPermissionsFrom}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ROLE_CODE_ALREADY_EXISTS};
     *         {@code PRIVILEGE_ESCALATION} when copying grants the caller does not hold
     */
    RoleSummary createRole(CreateRoleCommand command);

    /** @throws com.stockflow.common.error.BusinessException {@code SYSTEM_ROLE_IMMUTABLE}, {@code OPTIMISTIC_LOCK} */
    RoleSummary updateRole(UpdateRoleCommand command);

    /** @throws com.stockflow.common.error.BusinessException {@code SYSTEM_ROLE_IMMUTABLE}, {@code ROLE_IN_USE} */
    void deleteRole(String roleCode);

    /**
     * Assign a role to a user. Idempotent: assigning an already-held role is a no-op.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRIVILEGE_ESCALATION} when the role
     *         grants something the caller lacks or the account holds more than the caller;
     *         {@code OWN_ACCOUNT_NOT_MANAGEABLE}; {@code ROLE_NOT_ASSIGNABLE} for CUSTOMER
     */
    void assignRole(UUID userId, String roleCode);

    /**
     * Revoke a role from a user. Idempotent: revoking one not held is a no-op.
     *
     * @throws com.stockflow.common.error.BusinessException {@code LAST_SYSTEM_ADMIN} and the rules of
     *         {@link #assignRole}
     */
    void revokeRole(UUID userId, String roleCode);

    /**
     * The ids of the roles the account holds, empty for an unknown account. For a module whose own table is keyed by
     * role (procurement's approval limits): the table is read in that module's schema and only the key crosses here,
     * so no role name is ever compared in code.
     */
    List<UUID> roleIdsOf(UUID userId);

    /** Staff accounts (customers on request), paginated (SCRUM-454). */
    PageResponse<StaffUser> listUsers(ListUsersQuery query);

    /** @throws com.stockflow.common.error.BusinessException {@code USER_NOT_FOUND} */
    StaffUser user(UUID userId);

    /**
     * The name to show for each user that exists — full name, or the username when none was given —
     * in one query (SCRUM-464). For screens that show who did something; it reveals nothing a
     * user's own id does not, so it needs no admin permission, unlike {@link #user}. Locked and
     * disabled users keep their name.
     */
    java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds);

    /**
     * Create a staff account with its roles. The password is temporary: the user must change it.
     *
     * @throws com.stockflow.common.error.BusinessException {@code USERNAME_ALREADY_EXISTS},
     *         {@code USER_EMAIL_ALREADY_EXISTS}, {@code ROLE_NOT_FOUND}, {@code ROLE_NOT_ASSIGNABLE},
     *         {@code PRIVILEGE_ESCALATION}, {@code VALIDATION_FAILED} for a weak password
     */
    CreatedStaffUser createStaffUser(CreateStaffUserCommand command);

    /** @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK}, {@code USER_EMAIL_ALREADY_EXISTS} */
    StaffUser updateUser(UpdateUserCommand command);

    /**
     * Lock, unlock, disable or enable an account. Locking and disabling end its sessions.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVALID_USER_STATUS_TRANSITION},
     *         {@code LAST_SYSTEM_ADMIN}, {@code OWN_ACCOUNT_NOT_MANAGEABLE}, {@code PRIVILEGE_ESCALATION}
     */
    StaffUser changeUserStatus(UUID userId, UserStatusChange change);

    /**
     * Replace the warehouses a staff member is assigned to (SCRUM-457). Applies on their next
     * request; matters for holders of a WAREHOUSE-scoped role.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND} and the rules of
     *         {@link #updateUser}
     */
    StaffUser assignWarehouses(UUID userId, List<UUID> warehouseIds);

    /**
     * Set a new temporary password and end every session of the account.
     *
     * @param newPassword null to have one generated
     */
    PasswordReset resetPassword(UUID userId, String newPassword);

    /**
     * The profile of a user, for {@code GET /identity/me}. The login response carries only a token,
     * so this is how a client learns the name and e-mail to show.
     *
     * @throws com.stockflow.common.error.BusinessException {@code USER_NOT_FOUND}
     */
    UserProfile profile(UUID userId);

    /**
     * The ACTIVE staff assigned to {@code warehouseId} who hold at least one of {@code roleCodes}, by
     * name, for a screen that picks who a task goes to (SCRUM-466). Returns only id, display name and
     * the matching roles: it needs no user-management permission because it reveals no e-mail, phone
     * or status. Not paged on purpose: the answer is bounded by one warehouse's staff.
     */
    List<AssignableUser> assignableUsers(java.util.Set<String> roleCodes, UUID warehouseId);

    /**
     * The ACTIVE staff to notify about something in {@code warehouseId} (or system-wide when it is {@code null}),
     * by role (SCRUM-147): holders of at least one of {@code roleCodes} whose data scope reaches it - everyone whose
     * broadest role scope is ALL, plus, for a warehouse, those assigned to it. System-wide ({@code null}) means ALL only.
     * Anybody who holds the CUSTOMER role is never returned, whatever {@code roleCodes} says. One query, not paged:
     * bounded by the staff of the business.
     */
    java.util.List<StaffContact> notificationRecipients(java.util.Set<String> roleCodes, UUID warehouseId);

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

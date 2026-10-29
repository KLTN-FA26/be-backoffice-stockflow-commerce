package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RoleAuthorizationLookup;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.CreateRoleCommand;
import com.stockflow.identity.api.CreateStaffUserCommand;
import com.stockflow.identity.api.CreatedStaffUser;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.LoginCommand;
import com.stockflow.identity.api.PasswordReset;
import com.stockflow.identity.api.RoleSummary;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.identity.api.UpdateRoleCommand;
import com.stockflow.identity.api.UpdateUserCommand;
import com.stockflow.identity.api.UserStatusChange;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.WithCurrentUser;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Staff user management, custom roles and the "only hand out what you hold" rule (SCRUM-453),
 * against real Postgres and Redis.
 *
 * <p>The context and database are shared with every other integration test, so each test makes its
 * own accounts and roles with unique names, and puts back anything shared it touches.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
class UserAndRoleManagementIntegrationTest {

    private static final String STRONG = "Str0ngPassword";
    private static final String REASON = "Reason given by the test";

    @Autowired
    private IdentityService identity;

    @Autowired
    private RoleAuthorizationLookup lookup;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @Autowired
    private com.stockflow.common.security.UserWarehouseLookup warehouseLookup;

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private CreatedStaffUser create(String... roles) {
        String name = unique("u.");
        return identity.createStaffUser(new CreateStaffUserCommand(name, name + "@example.com", "Test " + name,
                null, List.of(roles), null));
    }

    /** A caller holding exactly what {@code roles} grant, as RequiresPermissionAspect would install. */
    private CurrentUser actor(UUID id, Role... roles) {
        Set<PermissionCode> permissions = lookup.resolve(
                java.util.Arrays.stream(roles).map(Role::authority).toList()).permissions();
        return new CurrentUser(id, "actor", Set.of(roles), permissions, DataScope.ALL, Set.of());
    }

    private <T> T as(CurrentUser user, Supplier<T> action) {
        return WithCurrentUser.call(user, DataScope.ALL, action);
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }

    // ---- staff user CRUD ---------------------------------------------------------------------

    @Test
    void createsAStaffAccountWithAGeneratedTemporaryPasswordThatSignsIn() {
        CreatedStaffUser created = create("WAREHOUSE_STAFF");

        assertThat(created.temporaryPassword()).hasSize(16);
        assertThat(created.user().roles()).containsExactly("WAREHOUSE_STAFF");
        assertThat(created.user().mustChangePassword()).isTrue();
        assertThat(created.user().status()).isEqualTo("ACTIVE");

        var token = identity.login(new LoginCommand(created.user().username(), created.temporaryPassword(),
                "127.0.0.1", "test"));
        assertThat(token.accessToken()).isNotBlank();
        assertThat(identity.profile(created.user().userId()).mustChangePassword()).isTrue();
    }

    /**
     * ScopeGuards.isBackOffice is "not a portal customer" (a staff member holding only a role created at run time has
     * no Role constant to show): that is only sound because CUSTOMER never sits next to another role.
     */
    @Test
    void customerIsNeverCombinedWithAnotherRole() {
        StaffUser clerk = create("SALES_STAFF").user();

        refused(ErrorCode.ROLE_NOT_ASSIGNABLE, () -> identity.assignRole(clerk.userId(), "CUSTOMER"));
        refused(ErrorCode.ROLE_NOT_ASSIGNABLE, () -> identity.createStaffUser(new CreateStaffUserCommand(
                unique("x."), unique("x") + "@example.com", null, null, List.of("SALES_STAFF", "CUSTOMER"), null)));
        assertThat(roleCodes(clerk.userId())).containsExactly("SALES_STAFF");
    }

    @Test
    void refusesDuplicatesCustomerRoleAndWeakPasswords() {
        StaffUser first = create("SALES_STAFF").user();

        refused(ErrorCode.USERNAME_ALREADY_EXISTS, () -> identity.createStaffUser(new CreateStaffUserCommand(
                first.username().toUpperCase(Locale.ROOT), unique("x") + "@example.com", null, null,
                List.of("SALES_STAFF"), null)));
        refused(ErrorCode.USER_EMAIL_ALREADY_EXISTS, () -> identity.createStaffUser(new CreateStaffUserCommand(
                unique("x."), first.email().toUpperCase(Locale.ROOT), null, null, List.of("SALES_STAFF"), null)));
        refused(ErrorCode.ROLE_NOT_ASSIGNABLE, () -> identity.createStaffUser(new CreateStaffUserCommand(
                unique("x."), unique("x") + "@example.com", null, null, List.of("CUSTOMER"), null)));
        refused(ErrorCode.ROLE_NOT_FOUND, () -> identity.createStaffUser(new CreateStaffUserCommand(
                unique("x."), unique("x") + "@example.com", null, null, List.of("NO_SUCH_ROLE"), null)));
        refused(ErrorCode.VALIDATION_FAILED, () -> identity.createStaffUser(new CreateStaffUserCommand(
                unique("x."), unique("x") + "@example.com", null, "short", List.of("SALES_STAFF"), null)));
    }

    @Test
    void listsStaffWithFiltersAndLeavesCustomersOutUnlessAsked() {
        StaffUser staff = create("QC_STAFF").user();
        String customerEmail = unique("c") + "@example.com";
        UUID customerId = identity.registerCustomer(
                new com.stockflow.identity.api.RegisterAccountCommand(customerEmail, STRONG, "Customer")).userId();

        var byRole = identity.listUsers(new ListUsersQuery(staff.username(), null, "QC_STAFF", false, 0, 20, null));
        assertThat(byRole.items()).extracting(StaffUser::userId).containsExactly(staff.userId());

        var staffOnly = identity.listUsers(new ListUsersQuery(customerEmail, null, null, false, 0, 20, null));
        assertThat(staffOnly.items()).isEmpty();
        var withCustomers = identity.listUsers(new ListUsersQuery(customerEmail, null, null, true, 0, 20, null));
        assertThat(withCustomers.items()).extracting(StaffUser::userId).containsExactly(customerId);

        refused(ErrorCode.VALIDATION_FAILED,
                () -> identity.listUsers(new ListUsersQuery(null, "SLEEPING", null, false, 0, 20, null)));
        refused(ErrorCode.STAFF_ACCOUNT_REQUIRED,
                () -> identity.changeUserStatus(customerId, UserStatusChange.LOCK, REASON));
    }

    @Test
    void editsTheProfileOnlyAtTheLoadedVersion() {
        StaffUser user = create("ACCOUNTANT").user();

        StaffUser edited = identity.updateUser(new UpdateUserCommand(user.userId(), user.version(),
                "Renamed." + user.email(), "Renamed"));
        assertThat(edited.fullName()).isEqualTo("Renamed");
        assertThat(edited.email()).isEqualTo(("renamed." + user.email()).toLowerCase(Locale.ROOT));
        assertThat(edited.version()).isGreaterThan(user.version());

        refused(ErrorCode.OPTIMISTIC_LOCK, () -> identity.updateUser(
                new UpdateUserCommand(user.userId(), user.version(), null, "Stale")));
    }

    @Test
    void lockingAndDisablingEndSessionsAndBlockSignIn() {
        CreatedStaffUser created = create("INVENTORY_PLANNER");
        UUID id = created.user().userId();
        identity.login(new LoginCommand(created.user().username(), created.temporaryPassword(), "127.0.0.1", "t"));

        assertThat(identity.changeUserStatus(id, UserStatusChange.LOCK, REASON).status()).isEqualTo("LOCKED");
        assertThat(liveSessions(id)).isZero();
        refused(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> identity.login(
                new LoginCommand(created.user().username(), created.temporaryPassword(), "127.0.0.1", "t")));
        refused(ErrorCode.INVALID_USER_STATUS_TRANSITION, () -> identity.changeUserStatus(id, UserStatusChange.ENABLE, REASON));

        identity.changeUserStatus(id, UserStatusChange.UNLOCK, REASON);
        identity.changeUserStatus(id, UserStatusChange.DISABLE, REASON);
        refused(ErrorCode.ACCOUNT_NOT_ACTIVE, () -> identity.login(
                new LoginCommand(created.user().username(), created.temporaryPassword(), "127.0.0.1", "t")));
        assertThat(identity.changeUserStatus(id, UserStatusChange.ENABLE, REASON).status()).isEqualTo("ACTIVE");
    }

    // ---- SCRUM-474 LOCK-REASON ----------------------------------------------------------------

    private List<String> auditDetails(UUID userId) {
        return jdbc.queryForList("""
                SELECT details FROM platform.audit_log
                 WHERE resource_type = 'user' AND resource_id = ? AND action = 'TRANSITION' AND outcome = 'SUCCESS'
                 ORDER BY occurred_at, id""", String.class, userId.toString());
    }

    @Test
    void everyStatusActionNeedsAReasonOf5To500CharactersAndNothingChangesWithoutOne() {
        CreatedStaffUser created = create("ACCOUNTANT");
        UUID id = created.user().userId();

        for (String bad : new String[]{null, "", "    ", "abcd", " abcd ", "x".repeat(501)}) {
            for (UserStatusChange change : UserStatusChange.values()) {
                refused(ErrorCode.VALIDATION_FAILED, () -> identity.changeUserStatus(id, change, bad));
            }
        }

        assertThat(identity.user(id).status()).isEqualTo("ACTIVE");
        assertThat(identity.user(id).latestStatusChange()).isNull();
        assertThat(identity.statusHistory(id, 0, 20).items()).isEmpty();
        assertThat(auditDetails(id)).isEmpty();
        // the bounds themselves are fine
        identity.changeUserStatus(id, UserStatusChange.LOCK, "abcde");
        identity.changeUserStatus(id, UserStatusChange.UNLOCK, "x".repeat(500));
    }

    @Test
    void theHistoryKeepsEveryActionNewestFirstAndTheLatestShowsOnDetailAndList() {
        CreatedStaffUser created = create("INVENTORY_PLANNER");
        UUID id = created.user().userId();
        UUID actorId = create("SALES_STAFF").user().userId();
        CurrentUser admin = new CurrentUser(actorId, "an.admin", Set.of(Role.SYSTEM_ADMIN),
                lookup.resolve(List.of(Role.SYSTEM_ADMIN.authority())).permissions(), DataScope.ALL, Set.of());

        as(admin, () -> identity.changeUserStatus(id, UserStatusChange.LOCK, "  Nghi ngờ lộ mật khẩu  "));
        as(admin, () -> identity.changeUserStatus(id, UserStatusChange.UNLOCK, "Đã xác minh với chủ tài khoản"));
        as(admin, () -> identity.changeUserStatus(id, UserStatusChange.DISABLE, "Nghỉ việc ngày 29/10"));

        var history = identity.statusHistory(id, 0, 20).items();
        assertThat(history).extracting(e -> e.action()).containsExactly(
                UserStatusChange.DISABLE, UserStatusChange.UNLOCK, UserStatusChange.LOCK);
        assertThat(history).extracting(e -> e.fromStatus() + ">" + e.toStatus())
                .containsExactly("ACTIVE>DISABLED", "LOCKED>ACTIVE", "ACTIVE>LOCKED");
        assertThat(history).extracting(e -> e.reason()).containsExactly(
                "Nghỉ việc ngày 29/10", "Đã xác minh với chủ tài khoản", "Nghi ngờ lộ mật khẩu");
        assertThat(history).allSatisfy(e -> {
            assertThat(e.actorId()).isEqualTo(actorId);
            assertThat(e.actorUsername()).isEqualTo("an.admin");
            assertThat(e.changedAt()).isNotNull();
        });

        StaffUser detail = identity.user(id);
        assertThat(detail.latestStatusChange().action()).isEqualTo(UserStatusChange.DISABLE);
        assertThat(detail.latestStatusChange().reason()).isEqualTo("Nghỉ việc ngày 29/10");
        StaffUser inList = identity.listUsers(new ListUsersQuery(created.user().username(), null, null, false, 0, 20, null))
                .items().get(0);
        assertThat(inList.latestStatusChange()).isEqualTo(detail.latestStatusChange());
        // an account nobody touched has none
        assertThat(create("ACCOUNTANT").user().latestStatusChange()).isNull();

        assertThat(identity.statusHistory(id, 0, 2).items()).hasSize(2);
        assertThat(identity.statusHistory(id, 1, 2).items()).hasSize(1);
        refused(ErrorCode.USER_NOT_FOUND, () -> identity.statusHistory(UUID.randomUUID(), 0, 20));
    }

    @Test
    void theAuditEntryTellsLockFromDisableAndCarriesTheReason() {
        UUID id = create("QC_STAFF").user().userId();

        identity.changeUserStatus(id, UserStatusChange.LOCK, "Đăng nhập bất thường");
        identity.changeUserStatus(id, UserStatusChange.UNLOCK, "Đã kiểm tra, an toàn");
        identity.changeUserStatus(id, UserStatusChange.DISABLE, "Hết hợp đồng");
        identity.changeUserStatus(id, UserStatusChange.ENABLE, "Ký lại hợp đồng");

        assertThat(auditDetails(id)).containsExactly(
                "{\"change\":\"LOCK\",\"reason\":\"Đăng nhập bất thường\"}",
                "{\"change\":\"UNLOCK\",\"reason\":\"Đã kiểm tra, an toàn\"}",
                "{\"change\":\"DISABLE\",\"reason\":\"Hết hợp đồng\"}",
                "{\"change\":\"ENABLE\",\"reason\":\"Ký lại hợp đồng\"}");
    }

    @Test
    void aRefusedTransitionLeavesNoHistoryRowAndAFailureAuditEntryWithTheReason() {
        UUID id = create("QC_STAFF").user().userId();

        identity.changeUserStatus(id, UserStatusChange.DISABLE, "Tạm dừng");
        refused(ErrorCode.INVALID_USER_STATUS_TRANSITION,
                () -> identity.changeUserStatus(id, UserStatusChange.LOCK, "Tạm dừng"));

        assertThat(identity.statusHistory(id, 0, 20).items()).extracting(e -> e.action())
                .containsExactly(UserStatusChange.DISABLE);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'user' AND resource_id = ?
                   AND outcome = 'FAILURE' AND details LIKE '%"context":{"change":"LOCK","reason":"Tạm dừng"}%'""",
                Long.class, id.toString())).isEqualTo(1);
    }

    @Test
    void theAutomaticLockAfterWrongPasswordsTakesNoReasonAndWritesNoHistory() {
        CreatedStaffUser created = create("ORDER_COORDINATOR");
        for (int i = 0; i < 5; i++) {
            refused(ErrorCode.UNAUTHORIZED,
                    () -> identity.login(new LoginCommand(created.user().username(), "Wrong0Password", "2.2.2.2", "t")));
        }

        assertThat(identity.user(created.user().userId()).lockedUntil()).isNotNull();
        assertThat(identity.statusHistory(created.user().userId(), 0, 20).items()).isEmpty();
        assertThat(identity.user(created.user().userId()).latestStatusChange()).isNull();
    }

    @Test
    void historyRowsCanNeitherBeChangedNorDeleted() {
        UUID id = create("QC_STAFF").user().userId();
        identity.changeUserStatus(id, UserStatusChange.LOCK, "Đăng nhập bất thường");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE identity.user_status_change SET reason = 'rewritten history' WHERE user_id = ?", id)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update(
                "DELETE FROM identity.user_status_change WHERE user_id = ?", id)))
                .hasMessageContaining("append-only");
        // and the database states the length rule a second time
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO identity.user_status_change (id, user_id, action, from_status, to_status, reason,
                                                         actor_username, changed_at)
                VALUES (gen_random_uuid(), ?, 'LOCK', 'ACTIVE', 'LOCKED', 'abc', 'x', now())""", id)))
                .hasMessageContaining("ck_user_status_change_reason");
        // ... including for a reason padded with no-break spaces, which btrim without a character set would keep
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO identity.user_status_change (id, user_id, action, from_status, to_status, reason,
                                                         actor_username, changed_at)
                VALUES (gen_random_uuid(), ?, 'LOCK', 'ACTIVE', 'LOCKED', ?, 'x', now())""", id, "\u00a0\u00a0abc\u00a0\u00a0")))
                .hasMessageContaining("ck_user_status_change_reason");
        refused(ErrorCode.VALIDATION_FAILED,
                () -> identity.changeUserStatus(id, UserStatusChange.UNLOCK, "\u00a0\u00a0abc\u00a0\u00a0"));
        identity.changeUserStatus(id, UserStatusChange.UNLOCK, "\u00a0Đã xác minh\u00a0");
        assertThat(identity.statusHistory(id, 0, 20).items().get(0).reason()).isEqualTo("Đã xác minh");
        assertThat(identity.statusHistory(id, 0, 20).items()).hasSize(2);
    }

    @Test
    void aResetPasswordIsTemporaryAndSignsTheAccountOut() {
        CreatedStaffUser created = create("PROCUREMENT_STAFF");
        UUID id = created.user().userId();
        identity.login(new LoginCommand(created.user().username(), created.temporaryPassword(), "127.0.0.1", "t"));

        PasswordReset reset = identity.resetPassword(id, null);

        assertThat(reset.temporaryPassword()).hasSize(16);
        assertThat(reset.sessionsEnded()).isEqualTo(1);
        refused(ErrorCode.UNAUTHORIZED, () -> identity.login(
                new LoginCommand(created.user().username(), created.temporaryPassword(), "127.0.0.1", "t")));
        identity.login(new LoginCommand(created.user().username(), reset.temporaryPassword(), "127.0.0.1", "t"));

        assertThat(identity.resetPassword(id, STRONG).temporaryPassword()).isNull();
    }

    @Test
    void fiveWrongPasswordsLockTheAccountUntilUnlocked() {
        CreatedStaffUser created = create("ORDER_COORDINATOR");
        String username = created.user().username();
        for (int i = 0; i < 5; i++) {
            refused(ErrorCode.UNAUTHORIZED, () -> identity.login(new LoginCommand(username, "Wrong0Password", "1.1.1.1", "t")));
        }

        // The right password now reveals the lock; a wrong one still only says "wrong".
        refused(ErrorCode.ACCOUNT_TEMPORARILY_LOCKED,
                () -> identity.login(new LoginCommand(username, created.temporaryPassword(), "1.1.1.1", "t")));
        refused(ErrorCode.UNAUTHORIZED,
                () -> identity.login(new LoginCommand(username, "Wrong0Password", "1.1.1.1", "t")));
        assertThat(identity.user(created.user().userId()).lockedUntil()).isNotNull();

        identity.changeUserStatus(created.user().userId(), UserStatusChange.UNLOCK, REASON);
        identity.login(new LoginCommand(username, created.temporaryPassword(), "1.1.1.1", "t"));
    }

    // ---- "only hand out what you hold" (SCRUM-456) -------------------------------------------

    @Test
    void anEcommerceAdminCannotPromoteAnyoneToSystemAdminNorTakeOverAnAdmin() {
        StaffUser shopAdmin = create("ECOMMERCE_ADMIN").user();
        StaffUser clerk = create("SALES_STAFF").user();
        StaffUser admin = create("SYSTEM_ADMIN").user();
        String empty = unique("EMPTY_").toUpperCase(Locale.ROOT);
        identity.createRole(new CreateRoleCommand(empty, "Nothing", null, null, null));
        StaffUser newcomer = create(empty).user();
        CurrentUser caller = actor(shopAdmin.userId(), Role.ECOMMERCE_ADMIN);

        // The newcomer holds nothing, so the shop admin may manage them — but not hand them more
        // than the shop admin holds.
        refused(ErrorCode.PRIVILEGE_ESCALATION, () -> as(caller, () -> {
            identity.assignRole(newcomer.userId(), "SYSTEM_ADMIN");
            return null;
        }));
        StaffUser renamed = as(caller, () -> identity.updateUser(
                new UpdateUserCommand(newcomer.userId(), newcomer.version(), null, "Managed by the shop admin")));
        assertThat(renamed.fullName()).isEqualTo("Managed by the shop admin");
        refused(ErrorCode.OWN_ACCOUNT_NOT_MANAGEABLE, () -> as(caller, () -> {
            identity.assignRole(shopAdmin.userId(), "SALES_STAFF");
            return null;
        }));
        refused(ErrorCode.PRIVILEGE_ESCALATION, () -> as(caller, () -> identity.resetPassword(admin.userId(), null)));
        refused(ErrorCode.PRIVILEGE_ESCALATION,
                () -> as(caller, () -> identity.changeUserStatus(admin.userId(), UserStatusChange.LOCK, REASON)));
        // SALES_STAFF grants sales permissions the shop admin does not hold.
        refused(ErrorCode.PRIVILEGE_ESCALATION, () -> as(caller, () -> {
            identity.assignRole(clerk.userId(), "SALES_STAFF");
            return null;
        }));
        refused(ErrorCode.PRIVILEGE_ESCALATION, () -> as(caller, () -> identity.createStaffUser(
                new CreateStaffUserCommand(unique("x."), unique("x") + "@example.com", null, null, List.of("SYSTEM_ADMIN"), null))));

        assertThat(roleCodes(shopAdmin.userId())).containsExactly("ECOMMERCE_ADMIN");
    }

    @Test
    void anEcommerceAdminCannotTickAPermissionItDoesNotHoldOntoItsOwnRole() {
        StaffUser shopAdmin = create("ECOMMERCE_ADMIN").user();
        CurrentUser caller = actor(shopAdmin.userId(), Role.ECOMMERCE_ADMIN);
        String custom = unique("R_").toUpperCase(Locale.ROOT);
        identity.createRole(new CreateRoleCommand(custom, "Custom", null, null, null));

        refused(ErrorCode.PRIVILEGE_ESCALATION,
                () -> as(caller, () -> identity.grantPermission(custom, "procurement-purchase-orders:APPROVE")));
        RoleMatrixView ok = as(caller, () -> identity.grantPermission(custom, "product-products:READ"));
        assertThat(granted(ok)).contains("product-products:READ");
        identity.deleteRole(custom);
    }

    @Test
    void aSystemAdminManagesEveryoneButNeverLeavesThePlatformWithoutOne() {
        StaffUser admin = create("SYSTEM_ADMIN").user();
        StaffUser other = create("SYSTEM_ADMIN").user();
        CurrentUser caller = actor(admin.userId(), Role.SYSTEM_ADMIN);

        List<UUID> parked = parkOtherActiveAdmins(admin.userId(), other.userId());
        try {
            as(caller, () -> {
                identity.revokeRole(other.userId(), "SYSTEM_ADMIN");
                return null;
            });
            // admin is now the only active System Admin: the same caller cannot be locked by anyone,
            // and nobody may take the role away from it.
            refused(ErrorCode.LAST_SYSTEM_ADMIN, () -> identity.revokeRole(admin.userId(), "SYSTEM_ADMIN"));
            refused(ErrorCode.LAST_SYSTEM_ADMIN, () -> identity.changeUserStatus(admin.userId(), UserStatusChange.LOCK, REASON));
            refused(ErrorCode.LAST_SYSTEM_ADMIN, () -> identity.changeUserStatus(admin.userId(), UserStatusChange.DISABLE, REASON));
        } finally {
            parked.forEach(id -> jdbc.update("UPDATE identity.app_user SET status = 'ACTIVE' WHERE id = ?", id));
        }
    }

    // ---- custom roles (SCRUM-455) ------------------------------------------------------------

    @Test
    void aCustomRoleCanBeCreatedCopiedGrantedRenamedAndDeletedWhenUnused() {
        String code = unique("shift_lead_").toUpperCase(Locale.ROOT);
        RoleSummary created = identity.createRole(new CreateRoleCommand(code, "Shift lead", "Floor lead", "WAREHOUSE_STAFF", null));
        assertThat(created.system()).isFalse();
        assertThat(lookup.resolve(List.of(code)).permissions())
                .isEqualTo(lookup.resolve(List.of("WAREHOUSE_STAFF")).permissions());

        identity.grantPermission(code, "inventory-stock-adjustments:APPROVE");
        assertThat(lookup.resolve(List.of(code)).permissions())
                .contains(PermissionCode.parse("inventory-stock-adjustments:APPROVE"));
        identity.revokePermission(code, "inventory-stock-adjustments:APPROVE");
        assertThat(lookup.resolve(List.of(code)).permissions())
                .doesNotContain(PermissionCode.parse("inventory-stock-adjustments:APPROVE"));

        CreatedStaffUser holder = create(code);
        var token = identity.login(new LoginCommand(holder.user().username(), holder.temporaryPassword(), "1.1.1.1", "t"));
        assertThat(token.accessToken()).isNotBlank();

        RoleSummary current = identity.listRoles().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
        assertThat(current.holderCount()).isEqualTo(1);
        RoleSummary renamed = identity.updateRole(new UpdateRoleCommand(code, current.version(), "Shift lead (night)", null, null));
        assertThat(renamed.name()).isEqualTo("Shift lead (night)");
        refused(ErrorCode.OPTIMISTIC_LOCK,
                () -> identity.updateRole(new UpdateRoleCommand(code, current.version(), "Stale", null, null)));

        refused(ErrorCode.ROLE_IN_USE, () -> identity.deleteRole(code));
        identity.revokeRole(holder.user().userId(), code);
        identity.deleteRole(code);
        refused(ErrorCode.ROLE_NOT_FOUND, () -> identity.roleMatrix(code));
    }

    @Test
    void systemRolesAndDuplicateCodesAreRefused() {
        refused(ErrorCode.SYSTEM_ROLE_IMMUTABLE, () -> identity.deleteRole("WAREHOUSE_STAFF"));
        refused(ErrorCode.SYSTEM_ROLE_IMMUTABLE,
                () -> identity.updateRole(new UpdateRoleCommand("SALES_STAFF", 0, "Sellers", null, null)));
        refused(ErrorCode.ROLE_CODE_ALREADY_EXISTS,
                () -> identity.createRole(new CreateRoleCommand("ACCOUNTANT", "Dup", null, null, null)));
        assertThat(identity.listRoles()).filteredOn(RoleSummary::system)
                .extracting(RoleSummary::code).contains("SYSTEM_ADMIN", "CUSTOMER", "PRODUCTION_STAFF");
    }

    // ---- warehouse data scope (SCRUM-457) -----------------------------------------------------

    @Test
    void warehouseRolesAreLimitedToTheirAssignedWarehousesResolvedPerRequest() {
        assertThat(lookup.resolve(List.of("WAREHOUSE_STAFF")).scope()).isEqualTo(DataScope.WAREHOUSE);
        assertThat(lookup.resolve(List.of("QC_STAFF", "PRODUCTION_STAFF")).scope()).isEqualTo(DataScope.WAREHOUSE);
        // The broadest of a user's roles: a clerk who also plans plans across warehouses.
        assertThat(lookup.resolve(List.of("WAREHOUSE_STAFF", "INVENTORY_PLANNER")).scope()).isEqualTo(DataScope.ALL);

        StaffUser clerk = create("WAREHOUSE_STAFF").user();
        assertThat(clerk.warehouseIds()).isEmpty();
        assertThat(warehouseLookup.warehousesOf(clerk.userId()).ids()).isEmpty();

        StaffUser assigned = identity.assignWarehouses(clerk.userId(),
                List.of(com.stockflow.support.DemoData.WAREHOUSE_HCM, com.stockflow.support.DemoData.WAREHOUSE_HCM));
        assertThat(assigned.warehouseIds()).containsExactly(com.stockflow.support.DemoData.WAREHOUSE_HCM);
        assertThat(warehouseLookup.warehousesOf(clerk.userId()).prefixes()).containsExactly("HCM");

        refused(ErrorCode.WAREHOUSE_NOT_FOUND,
                () -> identity.assignWarehouses(clerk.userId(), List.of(UUID.randomUUID())));
        assertThat(identity.assignWarehouses(clerk.userId(), List.of()).warehouseIds()).isEmpty();
        assertThat(warehouseLookup.warehousesOf(clerk.userId()).prefixes()).isEmpty();

        CreatedStaffUser withWarehouse = identity.createStaffUser(new CreateStaffUserCommand(unique("w."),
                unique("w") + "@example.com", null, null, List.of("QC_STAFF"),
                List.of(com.stockflow.support.DemoData.WAREHOUSE_HCM)));
        assertThat(withWarehouse.user().warehouseIds()).containsExactly(com.stockflow.support.DemoData.WAREHOUSE_HCM);
    }

    // ---- who a warehouse task can be given to (SCRUM-466) ---------------------------------------

    @Test
    void assignableUsersAreTheActiveWarehouseRolesOfOneWarehouseAndRevealNothingElse() {
        UUID hcm = com.stockflow.support.DemoData.WAREHOUSE_HCM;
        UUID other = UUID.randomUUID();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Assignee test warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""",
                other, "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)));
        List<UUID> inHcm = List.of(hcm);

        StaffUser picker = identity.createStaffUser(new CreateStaffUserCommand(unique("a."), unique("a") + "@example.com",
                "An Picker", null, List.of("WAREHOUSE_STAFF"), inHcm)).user();
        StaffUser manager = identity.createStaffUser(new CreateStaffUserCommand(unique("a."), unique("a") + "@example.com",
                null, null, List.of("WAREHOUSE_MANAGER", "QC_STAFF"), inHcm)).user();
        StaffUser sales = identity.createStaffUser(new CreateStaffUserCommand(unique("a."), unique("a") + "@example.com",
                "Sales Person", null, List.of("SALES_STAFF"), inHcm)).user();
        StaffUser elsewhere = identity.createStaffUser(new CreateStaffUserCommand(unique("a."),
                unique("a") + "@example.com", "Other Picker", null, List.of("WAREHOUSE_STAFF"), List.of(other))).user();
        StaffUser unassigned = identity.createStaffUser(new CreateStaffUserCommand(unique("a."),
                unique("a") + "@example.com", "Nowhere Picker", null, List.of("WAREHOUSE_STAFF"), List.of())).user();
        StaffUser disabled = identity.createStaffUser(new CreateStaffUserCommand(unique("a."),
                unique("a") + "@example.com", "Gone Picker", null, List.of("WAREHOUSE_STAFF"), inHcm)).user();
        tx.executeWithoutResult(status -> jdbc.update(
                "UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?", disabled.userId()));

        var found = identity.assignableUsers(Set.of("WAREHOUSE_STAFF", "WAREHOUSE_MANAGER"), hcm);

        assertThat(found).extracting(u -> u.userId()).contains(picker.userId(), manager.userId())
                .doesNotContain(sales.userId(), elsewhere.userId(), unassigned.userId(), disabled.userId());
        var pickerRow = found.stream().filter(u -> u.userId().equals(picker.userId())).findFirst().orElseThrow();
        assertThat(pickerRow.displayName()).isEqualTo("An Picker");
        assertThat(pickerRow.roles()).containsExactly("WAREHOUSE_STAFF");
        var managerRow = found.stream().filter(u -> u.userId().equals(manager.userId())).findFirst().orElseThrow();
        // No full name given: the username is shown. Only the roles asked for are listed, not QC_STAFF.
        assertThat(managerRow.displayName()).isEqualTo(manager.username());
        assertThat(managerRow.roles()).containsExactly("WAREHOUSE_MANAGER");

        assertThat(identity.assignableUsers(Set.of("WAREHOUSE_STAFF"), other)).extracting(u -> u.userId())
                .containsExactly(elsewhere.userId());
        assertThat(identity.assignableUsers(Set.of(), hcm)).isEmpty();
        assertThat(identity.assignableUsers(Set.of("WAREHOUSE_STAFF"), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aCustomRoleCarriesTheDataScopeItIsGiven() {
        String code = unique("FLOOR_").toUpperCase(Locale.ROOT);
        RoleSummary created = identity.createRole(new CreateRoleCommand(code, "Floor", null, null, "warehouse"));
        assertThat(created.dataScope()).isEqualTo("WAREHOUSE");
        assertThat(lookup.resolve(List.of(code)).scope()).isEqualTo(DataScope.WAREHOUSE);

        RoleSummary widened = identity.updateRole(new UpdateRoleCommand(code, created.version(), "Floor", null, "ALL"));
        assertThat(widened.dataScope()).isEqualTo("ALL");
        assertThat(lookup.resolve(List.of(code)).scope()).isEqualTo(DataScope.ALL);

        refused(ErrorCode.VALIDATION_FAILED,
                () -> identity.createRole(new CreateRoleCommand(unique("X_").toUpperCase(Locale.ROOT), "x", null, null, "TEAM")));
        identity.deleteRole(code);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private int liveSessions(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM identity.user_session WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private List<String> roleCodes(UUID userId) {
        return jdbc.queryForList("""
                SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? ORDER BY r.code""", String.class, userId);
    }

    /** Disables every other active System Admin for the test; returns them so they can be restored. */
    private List<UUID> parkOtherActiveAdmins(UUID... keep) {
        List<UUID> others = jdbc.queryForList("""
                SELECT u.id FROM identity.app_user u
                JOIN identity.user_role ur ON ur.user_id = u.id
                JOIN identity.app_role r ON r.id = ur.role_id
                WHERE r.code = 'SYSTEM_ADMIN' AND u.status = 'ACTIVE'""", UUID.class);
        others.removeAll(List.of(keep));
        others.forEach(id -> jdbc.update("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = ?", id));
        return others;
    }

    private static List<String> granted(RoleMatrixView matrix) {
        List<String> codes = new java.util.ArrayList<>();
        matrix.groups().forEach(group -> group.resources().forEach(resource -> resource.actions().stream()
                .filter(RoleMatrixView.ActionChip::granted)
                .forEach(chip -> codes.add(resource.code() + ":" + chip.action().name()))));
        return codes;
    }
}

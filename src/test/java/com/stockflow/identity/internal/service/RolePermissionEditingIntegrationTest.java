package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RoleAuthorizationLookup;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Editing a role's grants against real Postgres and Redis (ADR-0008): the version compare-and-set,
 * the lockout guard, and above all that a holder of the role sees the change on the very next
 * lookup even though the cache was warm.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
class RolePermissionEditingIntegrationTest {

    private static final String ROLE = "INVENTORY_PLANNER";
    private static final String PO_APPROVE = "procurement-purchase-orders:APPROVE";

    @Autowired
    private IdentityService identity;

    @Autowired
    private RoleAuthorizationLookup lookup;

    @Autowired
    private RoleAuthorizationCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<String> original;

    @BeforeEach
    void remember() {
        original = granted(identity.roleMatrix(ROLE));
    }

    /** Put the seeded grants back: the context, and so the database, is shared with other tests. */
    @AfterEach
    void restore() {
        RoleMatrixView now = identity.roleMatrix(ROLE);
        if (!Set.copyOf(granted(now)).equals(Set.copyOf(original))) {
            identity.updateRolePermissions(new UpdateRolePermissionsCommand(ROLE, now.version(), original));
        }
        Set<String> keys = redis.keys("*authz*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static List<String> granted(RoleMatrixView matrix) {
        List<String> codes = new ArrayList<>();
        matrix.groups().forEach(group -> group.resources().forEach(resource -> resource.actions().stream()
                .filter(RoleMatrixView.ActionChip::granted)
                .forEach(chip -> codes.add(resource.code() + ":" + chip.action().name()))));
        return codes;
    }

    private static List<String> plus(List<String> codes, String extra) {
        List<String> result = new ArrayList<>(codes);
        result.add(extra);
        return result;
    }

    private Set<PermissionCode> resolved() {
        return lookup.resolve(List.of(ROLE)).permissions();
    }

    @Test
    void aGrantReachesTheNextLookupEvenWithAWarmCache() {
        assertThat(resolved()).doesNotContain(PermissionCode.parse(PO_APPROVE));   // warms the cache
        RoleMatrixView before = identity.roleMatrix(ROLE);

        RoleMatrixView saved = identity.updateRolePermissions(
                new UpdateRolePermissionsCommand(ROLE, before.version(), plus(original, PO_APPROVE)));

        assertThat(saved.version()).isEqualTo(before.version() + 1);
        assertThat(granted(saved)).contains(PO_APPROVE);
        assertThat(identity.roleMatrix(ROLE).version()).isEqualTo(saved.version());
        assertThat(resolved()).contains(PermissionCode.parse(PO_APPROVE));

        identity.updateRolePermissions(new UpdateRolePermissionsCommand(ROLE, saved.version(), original));
        assertThat(resolved()).doesNotContain(PermissionCode.parse(PO_APPROVE));
    }

    private int grantRows(String role) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM identity.role_permission rp
                JOIN identity.app_role r ON r.id = rp.role_id WHERE r.code = ?""", Integer.class, role);
    }

    @Test
    void grantsTheScreenCannotShowSurviveASave() {
        // The seed grants permissions for modules not built yet; the matrix hides them.
        int before = grantRows("SALES_STAFF");
        RoleMatrixView matrix = identity.roleMatrix("SALES_STAFF");
        assertThat(granted(matrix)).hasSizeLessThan(before);

        RoleMatrixView saved = identity.updateRolePermissions(
                new UpdateRolePermissionsCommand("SALES_STAFF", matrix.version(), granted(matrix)));

        assertThat(saved.version()).isEqualTo(matrix.version() + 1);
        assertThat(grantRows("SALES_STAFF")).isEqualTo(before);
    }

    @Test
    void aSaveAgainstAVersionSomeoneElseMovedPastIsRefused() {
        long loaded = identity.roleMatrix(ROLE).version();
        identity.updateRolePermissions(new UpdateRolePermissionsCommand(ROLE, loaded, plus(original, PO_APPROVE)));

        assertThatThrownBy(() -> identity.updateRolePermissions(
                new UpdateRolePermissionsCommand(ROLE, loaded, original)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ROLE_PERMISSIONS_CHANGED));
        assertThat(granted(identity.roleMatrix(ROLE))).contains(PO_APPROVE);
    }

    @Test
    void unknownCodesAreRefusedAndNothingIsWritten() {
        long loaded = identity.roleMatrix(ROLE).version();

        assertThatThrownBy(() -> identity.updateRolePermissions(new UpdateRolePermissionsCommand(ROLE, loaded,
                List.of("procurement-purchase-orders:READ", "no-such-screen:READ", "not a code"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNKNOWN_PERMISSION);
                    assertThat(ex.getMessage()).contains("no-such-screen:READ", "not a code");
                });
        RoleMatrixView after = identity.roleMatrix(ROLE);
        assertThat(after.version()).isEqualTo(loaded);
        assertThat(granted(after)).containsExactlyInAnyOrderElementsOf(original);
    }

    @Test
    void theCustomerRoleIsManagedByMigrationsOnly() {
        RoleMatrixView customer = identity.roleMatrix("CUSTOMER");
        assertThat(customer.editable()).isFalse();
        assertThat(identity.roleMatrix(ROLE).editable()).isTrue();

        assertThatThrownBy(() -> identity.updateRolePermissions(
                new UpdateRolePermissionsCommand("CUSTOMER", customer.version(), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ROLE_NOT_EDITABLE));
    }

    private static final String MANAGE_RBAC = "identity-rbac:APPROVE";

    private static List<String> without(List<String> codes, String removed) {
        return codes.stream().filter(code -> !code.equals(removed)).toList();
    }

    /** SYSTEM_ADMIN always holds the right, so another role is free to give it up. */
    @Test
    void anotherRoleMayGiveUpManagingPermissionsWhileSystemAdminHoldsIt() {
        RoleMatrixView admin = identity.roleMatrix("ECOMMERCE_ADMIN");
        List<String> before = granted(admin);
        try {
            RoleMatrixView saved = identity.updateRolePermissions(new UpdateRolePermissionsCommand(
                    "ECOMMERCE_ADMIN", admin.version(), without(before, MANAGE_RBAC)));

            assertThat(granted(saved)).doesNotContain(MANAGE_RBAC);
        } finally {
            RoleMatrixView now = identity.roleMatrix("ECOMMERCE_ADMIN");
            identity.updateRolePermissions(new UpdateRolePermissionsCommand("ECOMMERCE_ADMIN", now.version(), before));
        }
    }

    /**
     * The guard itself, with SYSTEM_ADMIN's grant taken away for the duration: SYSTEM_ADMIN is not
     * editable through the service, so this is the only way left to make ECOMMERCE_ADMIN the last
     * holder, and the guard still has to hold if a migration ever leaves it that way.
     */
    @Test
    void theLastRoleAbleToManagePermissionsCannotGiveThatUp() {
        String systemAdminGrant = """
                FROM identity.role_permission rp
                USING identity.app_role r, identity.permission p
                WHERE rp.role_id = r.id AND rp.permission_id = p.id
                  AND r.code = 'SYSTEM_ADMIN' AND p.code = ?""";
        // Hikari runs with auto-commit off: a bare JdbcTemplate write is rolled back on release.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> jdbc.update("DELETE " + systemAdminGrant, MANAGE_RBAC));
        try {
            RoleMatrixView admin = identity.roleMatrix("ECOMMERCE_ADMIN");

            assertThatThrownBy(() -> identity.updateRolePermissions(new UpdateRolePermissionsCommand(
                    "ECOMMERCE_ADMIN", admin.version(), without(granted(admin), MANAGE_RBAC))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.RBAC_LOCKOUT));
            // Refused inside the transaction, so the version bump rolled back with it.
            assertThat(identity.roleMatrix("ECOMMERCE_ADMIN").version()).isEqualTo(admin.version());
        } finally {
            tx.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
                    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'test'
                    FROM identity.app_role r, identity.permission p
                    WHERE r.code = 'SYSTEM_ADMIN' AND p.code = ?
                    ON CONFLICT (role_id, permission_id) DO NOTHING""", MANAGE_RBAC));
        }
    }

    @Test
    void aFlushedRedisIsRefilledFromTheDatabase() {
        Set<PermissionCode> before = resolved();
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();

        assertThat(resolved()).isEqualTo(before);
        assertThat(redis.keys("*authz*")).isNotEmpty();
    }

    @Test
    void theVersionPointerNeverMovesBackwards() {
        long current = identity.roleMatrix(ROLE).version();
        resolved();
        String pointerKey = redis.keys("*authz:role-versions").iterator().next();

        cache.publishVersion(ROLE, current + 5);
        cache.publishVersion(ROLE, current + 1);   // a late after-commit callback

        assertThat(redis.opsForHash().get(pointerKey, ROLE)).isEqualTo(String.valueOf(current + 5));
    }

    /**
     * A grant set cached before data scopes existed has no scope member. It must be reloaded, not
     * read as ALL — otherwise a warehouse clerk would see every warehouse until the key expired.
     */
    @Test
    void aGrantSetCachedWithoutAScopeIsReloadedNotReadAsAll() {
        assertThat(lookup.resolve(List.of("WAREHOUSE_STAFF")).scope())
                .isEqualTo(com.stockflow.common.security.DataScope.WAREHOUSE);
        Set<String> keys = redis.keys("*authz:role:WAREHOUSE_STAFF:v*");
        assertThat(keys).isNotEmpty();
        keys.forEach(key -> redis.opsForSet().remove(key, "@WAREHOUSE"));

        assertThat(lookup.resolve(List.of("WAREHOUSE_STAFF")).scope())
                .isEqualTo(com.stockflow.common.security.DataScope.WAREHOUSE);
        keys.forEach(key -> assertThat(redis.opsForSet().isMember(key, "@WAREHOUSE")).isTrue());
    }
}

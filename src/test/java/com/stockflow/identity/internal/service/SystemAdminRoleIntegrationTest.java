package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RoleAuthorizationLookup;
import com.stockflow.common.security.RoleMatrixView;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UpdateRolePermissionsCommand;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM_ADMIN holds every permission, against the migrated database (V20260929000200).
 *
 * <p>The first test is the one that matters over time: a later migration that adds rows to
 * {@code identity.permission} without granting them to SYSTEM_ADMIN fails here, instead of an
 * administrator discovering a 403 in production.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
class SystemAdminRoleIntegrationTest {

    private static final String ROLE = Role.SYSTEM_ADMIN.authority();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleAuthorizationLookup lookup;

    @Autowired
    private IdentityService identity;

    @Autowired
    private PermissionCatalog catalog;

    @Test
    void holdsEveryPermissionRowInTheDatabase() {
        List<String> missing = jdbc.queryForList("""
                SELECT p.code FROM identity.permission p
                WHERE NOT EXISTS (
                    SELECT 1 FROM identity.role_permission rp
                    JOIN identity.app_role r ON r.id = rp.role_id
                    WHERE r.code = ? AND rp.permission_id = p.id)
                ORDER BY p.code""", String.class, ROLE);

        assertThat(missing)
                .as("permissions not granted to SYSTEM_ADMIN - grant them in the migration that added them")
                .isEmpty();
    }

    @Test
    void resolvesToEveryPermissionTheCatalogDeclares() {
        Set<PermissionCode> resolved = lookup.resolve(List.of(ROLE)).permissions();

        assertThat(resolved).containsAll(catalog.allPermissions());
    }

    @Test
    void itsMatrixIsFullAndReadOnly() {
        RoleMatrixView matrix = identity.roleMatrix(ROLE);

        assertThat(matrix.editable()).isFalse();
        assertThat(matrix.grantedCount()).isEqualTo(matrix.totalCount()).isPositive();
    }

    @Test
    void itsGrantsCannotBeEditedAtRuntime() {
        long version = identity.roleMatrix(ROLE).version();

        assertThatThrownBy(() -> identity.updateRolePermissions(
                new UpdateRolePermissionsCommand(ROLE, version, List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ROLE_NOT_EDITABLE));
        assertThat(identity.roleMatrix(ROLE).version()).isEqualTo(version);
    }

    /** The enum and the table must name the same roles: a JWT carries the codes, not the enum. */
    @Test
    void everyRoleInTheEnumIsSeededAndViceVersa() {
        List<String> seeded = jdbc.queryForList("SELECT code FROM identity.app_role", String.class);
        List<String> declared = Arrays.stream(Role.values()).map(Role::authority).toList();

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(declared);
    }
}

package com.stockflow.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Grants a seeded permission to roles for a test, as an administrator would in the role matrix: the rows, and the role
 * advanced in the same transaction, so the cached grants of the role's holders are read again (ADR-0008).
 *
 * <p>For a permission the migrations grant to no role but SYSTEM_ADMIN on purpose, such as {@code assistant-mcp:CREATE}
 * (opt-in, AQ-17). The grant stays for the rest of the test context, like any other test data; a role that already
 * holds it is left as it is.</p>
 */
public final class RoleGrants {

    private RoleGrants() {
    }

    public static void grant(JdbcTemplate jdbc, TransactionTemplate tx, String permissionCode, String... roleCodes) {
        tx.executeWithoutResult(status -> {
            for (String role : roleCodes) {
                int added = jdbc.update("""
                        INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
                        SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'test'
                        FROM identity.app_role role, identity.permission permission
                        WHERE role.code = ? AND permission.code = ?
                        ON CONFLICT (role_id, permission_id) DO NOTHING""", role, permissionCode);
                if (added > 0) {
                    jdbc.update("UPDATE identity.app_role SET version = version + 1 WHERE code = ?", role);
                }
            }
        });
    }
}

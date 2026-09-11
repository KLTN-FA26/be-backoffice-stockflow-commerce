package com.stockflow.support;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a {@link CurrentUser} for a test.
 *
 * <p>Without this, testing anything about permissions or data scope means assembling five sets by
 * hand at the top of every test method. That is enough friction that the scope tests do not get
 * written — which is how an authorisation rule ends up with no coverage at all.</p>
 *
 * <pre>
 * CurrentUser clerk = TestUsers.builder()
 *         .role(Role.WAREHOUSE_STAFF)
 *         .scope(DataScope.WAREHOUSE)
 *         .warehouses("HCM")
 *         .can("inventory-stock-items", Action.READ)
 *         .build();
 * </pre>
 *
 * @see WithCurrentUser for installing one into the security context
 */
public final class TestUsers {

    private TestUsers() {
    }

    /** A user with ALL scope and no permissions — the starting point for "may they?" tests. */
    public static CurrentUser admin() {
        return builder().role(Role.ECOMMERCE_ADMIN).scope(DataScope.ALL).build();
    }

    /** A customer: OWN scope, so they see only their own rows. */
    public static CurrentUser customer() {
        return builder().role(Role.CUSTOMER).scope(DataScope.OWN).build();
    }

    /** A clerk assigned to one warehouse. */
    public static CurrentUser warehouseStaff(String... warehouses) {
        return builder()
                .role(Role.WAREHOUSE_STAFF)
                .scope(DataScope.WAREHOUSE)
                .warehouses(warehouses)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private UUID userId = UUID.randomUUID();
        private String username = "test-user";
        private final Set<Role> roles = new LinkedHashSet<>();
        private final Set<PermissionCode> permissions = new LinkedHashSet<>();
        // OWN by default, deliberately: a test that forgets to set a scope gets the NARROWEST one,
        // so it fails visibly rather than passing because everything was permitted.
        private DataScope scope = DataScope.OWN;
        private final Set<String> warehouses = new LinkedHashSet<>();

        public Builder id(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder role(Role... values) {
            roles.addAll(Arrays.asList(values));
            return this;
        }

        public Builder scope(DataScope scope) {
            this.scope = scope;
            return this;
        }

        public Builder warehouses(String... codes) {
            warehouses.addAll(Arrays.asList(codes));
            return this;
        }

        /** Grants one permission, as {@code resource:ACTION}. */
        public Builder can(String resource, Action action) {
            permissions.add(PermissionCode.of(resource, action));
            return this;
        }

        public CurrentUser build() {
            return new CurrentUser(userId, username, Set.copyOf(roles), Set.copyOf(permissions),
                    scope, Set.copyOf(warehouses));
        }
    }
}

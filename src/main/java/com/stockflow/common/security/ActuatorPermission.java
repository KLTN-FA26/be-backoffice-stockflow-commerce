package com.stockflow.common.security;

/**
 * The permissions that open the operational actuator endpoints to a signed-in caller (V20261022000200, seeded for the
 * roles that reached them before: ECOMMERCE_ADMIN and SYSTEM_ADMIN). {@code platform-actuator:READ} covers GET and HEAD
 * on every exposed endpoint - the aggregate health with its details ({@code management.endpoint.health.roles}),
 * metrics, Prometheus, the module map; {@code platform-actuator:UPDATE} covers every other method on any of them (a
 * {@code POST /actuator/loggers/...} the day loggers is exposed). The liveness and readiness probes and
 * {@code /actuator/info} stay anonymous on their own filter chain
 * ({@link ResourceServerSecurityConfig#docsAndProbesFilterChain}).
 *
 * <p>URL rules, not {@link RequiresPermission}: actuator endpoints are not controllers. Declared here so the catalog -
 * and so the role matrix screen - can grant them like any other permission.</p>
 */
@PermissionResource(code = ActuatorPermission.RESOURCE, group = "Platform",
        label = "Actuator: READ = every GET (health details, metrics, modules); UPDATE = every other method",
        apiPath = "/actuator/**", actions = {Action.READ, Action.UPDATE})
public final class ActuatorPermission {

    public static final String RESOURCE = "platform-actuator";

    /** The authority a holder of {@code platform-actuator:READ} carries. */
    public static final String READ_AUTHORITY = RESOURCE + PermissionCode.SEPARATOR + "READ";

    /** The authority a holder of {@code platform-actuator:UPDATE} carries. */
    public static final String UPDATE_AUTHORITY = RESOURCE + PermissionCode.SEPARATOR + "UPDATE";

    private ActuatorPermission() {
    }
}

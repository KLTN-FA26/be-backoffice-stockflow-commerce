package com.stockflow.common.security;

/**
 * The permission that opens the operational actuator endpoints - the aggregate health with its details, metrics,
 * Prometheus, the module map - to a signed-in caller (V20261021001300, seeded for the roles that reached them before:
 * ECOMMERCE_ADMIN and SYSTEM_ADMIN). The liveness and readiness probes and {@code /actuator/info} stay anonymous on
 * their own filter chain ({@link ResourceServerSecurityConfig#docsAndProbesFilterChain}).
 *
 * <p>A URL rule, not a {@link RequiresPermission}: actuator endpoints are not controllers. Declared here so the catalog
 * - and so the role matrix screen - can grant it like any other permission.</p>
 */
@PermissionResource(code = ActuatorPermission.RESOURCE, group = "Platform", label = "Actuator: health details, metrics",
        apiPath = "/actuator", actions = {Action.READ})
public final class ActuatorPermission {

    public static final String RESOURCE = "platform-actuator";

    /** The authority {@code StockflowJwtAuthenticationConverter} grants a holder: {@code platform-actuator:READ}. */
    public static final String AUTHORITY = RESOURCE + PermissionCode.SEPARATOR + "READ";

    private ActuatorPermission() {
    }
}

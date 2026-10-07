package com.stockflow.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * SYSTEM_ADMIN reaches every other role.
 *
 * <p>Permissions are the authorisation model (ADR-0004), and SYSTEM_ADMIN holds every one of them.
 * But some endpoints also carry {@code @PreAuthorize("hasAnyAuthority('WAREHOUSE_STAFF', ...)")}
 * and the actuator is gated on {@code hasAuthority('ECOMMERCE_ADMIN')}; a role list knows nothing
 * of new roles, so without this the administrator was refused there with a 403. Spring Security
 * picks a {@code RoleHierarchy} bean up for both method security and URL rules, and applies it to
 * authorities as well as to roles.</p>
 *
 * <p>The authorities are the bare role codes ({@code StockflowJwtAuthenticationConverter} adds no
 * {@code ROLE_} prefix), hence {@code fromHierarchy} rather than a prefixing builder. Built from
 * {@link Role}, so a role added to the enum is covered without touching this class.</p>
 *
 * <p>Code that checks roles itself goes through {@link CurrentUser#hasRole}, which applies the same
 * rule.</p>
 */
@Configuration
public class RoleHierarchyConfig {

    @Bean
    public static RoleHierarchy roleHierarchy() {
        String hierarchy = Arrays.stream(Role.values())
                .filter(role -> role != Role.SYSTEM_ADMIN)
                .map(role -> Role.SYSTEM_ADMIN.authority() + " > " + role.authority())
                .collect(Collectors.joining("\n"));
        return RoleHierarchyImpl.fromHierarchy(hierarchy);
    }
}

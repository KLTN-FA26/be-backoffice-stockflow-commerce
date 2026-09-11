package com.stockflow.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Establishes a system data scope for every request when {@code stockflow.security.enabled=false}.
 *
 * <h2>The bug this closes</h2>
 *
 * <p>Turning security off conditions {@link RequiresPermissionAspect} away. That aspect is the only
 * thing that ever calls {@link DataScopeContext#set}, so with it gone <b>no scope is ever
 * established</b> — and {@link DataScopeSpecifications#forCurrentUser} treats a missing scope as a
 * programming error and throws {@link ScopeViolationException}.</p>
 *
 * <p>The result is the opposite of "security off": every list and every detail endpoint that goes
 * through {@code ScopedJpaRepository} answers 403, in exactly the environment — local development,
 * the {@code test} profile, the {@code app} container in docker-compose — where everything is
 * supposed to just work. {@link PermissiveSecurityConfig} fixes the same class of problem one layer
 * up, at the filter chain; this fixes it at the row-scope layer.</p>
 *
 * <h2>Why a synthetic user rather than relaxing the check</h2>
 *
 * <p>Making {@code forCurrentUser} fall back to ALL when no scope is present would remove the one
 * guarantee the whole mechanism rests on: that a scoped query without a scope fails loudly instead
 * of returning everything. That fallback would be live in production too, where the same missing
 * scope means a genuine authorisation bug. Supplying a scope here keeps the check strict and
 * confines the relaxation to the profile that already announces itself as insecure.</p>
 *
 * <p>The filter runs at {@code HIGHEST_PRECEDENCE + 10}, just inside {@code CorrelationIdFilter},
 * so the scope is in place before anything can query — and is removed in a {@code finally}, because
 * a scope left on a pooled thread is the leak this whole design exists to prevent.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "stockflow.security", name = "enabled", havingValue = "false")
public class UnsecuredDataScopeFilter extends OncePerRequestFilter {

    /**
     * A fixed identifier rather than a random one, so rows created during local development have a
     * stable, recognisable owner instead of a new "user" on every restart.
     *
     * <p>Every character has to be valid hex — {@code UUID.fromString} throws otherwise, and it
     * would throw during class initialisation, which surfaces as a bean creation failure with no
     * obvious connection to this constant. {@code dead} spells something and parses; {@code dev0}
     * does not.</p>
     */
    private static final UUID LOCAL_DEV_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000dead");

    private static final CurrentUser LOCAL_DEV_USER = new CurrentUser(
            LOCAL_DEV_USER_ID,
            "local-dev",
            Set.of(),
            Set.of(),
            DataScope.ALL,
            Set.of());

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        DataScopeContext.setSystemScope(LOCAL_DEV_USER);
        try {
            chain.doFilter(request, response);
        } finally {
            DataScopeContext.clear();
        }
    }
}

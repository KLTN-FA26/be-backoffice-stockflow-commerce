package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The chain used when {@code stockflow.security.enabled=false}.
 *
 * <h2>Why this has to exist, rather than simply having no chain</h2>
 *
 * <p>Turning security "off" by letting {@link ResourceServerSecurityConfig} condition itself away
 * does the <b>opposite</b> of what it looks like. With no {@code SecurityFilterChain} bean, Spring
 * Boot's {@code OAuth2ResourceServerAutoConfiguration} sees a default web-security situation and
 * installs its own chain: {@code anyRequest().authenticated()} plus JWT validation against
 * {@code jwk-set-uri}. So every request — including {@code /actuator/health} and Swagger UI —
 * requires a valid token from an issuer that is not running locally.</p>
 *
 * <p>The symptoms are exactly the ones the local profile promises to avoid: curl returns 401,
 * Swagger UI cannot call anything, and the {@code app} container in docker-compose never passes its
 * readiness probe, so {@code docker compose --profile app up} hangs and never converges.</p>
 *
 * <p>Declaring a permissive chain removes the ambiguity: "off" means a chain that permits
 * everything, not the absence of a chain.</p>
 *
 * <p>{@link UnsecuredDataScopeFilter} is the second half of the same job. This class opens the
 * filter chain; that one supplies the row scope, which is otherwise never established once
 * {@link RequiresPermissionAspect} conditions itself away — leaving every scoped query throwing
 * 403 behind a chain that permits everything.</p>
 *
 * <h2>This must never be active in a deployment</h2>
 *
 * <p>{@code application.yml} sets {@code stockflow.security.enabled: true} as the default, and
 * {@code application-prod.yml} sets it again explicitly. Only {@code local} and {@code test} switch
 * it off. The warning below is logged on every startup so that an instance running without
 * authentication can never do so quietly.</p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "stockflow.security", name = "enabled", havingValue = "false")
public class PermissiveSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(PermissiveSecurityConfig.class);

    @Bean
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        log.warn("""
                ============================================================
                SECURITY IS DISABLED (stockflow.security.enabled=false).
                Every endpoint is open and @RequiresPermission is NOT enforced.
                This is for local development only.
                ============================================================""");

        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(new org.springframework.web.cors
                        .UrlBasedCorsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}

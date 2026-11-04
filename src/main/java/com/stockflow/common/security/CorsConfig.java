package com.stockflow.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The CORS policy, shared by both security chains.
 *
 * <p>Its own unconditional class because {@link ResourceServerSecurityConfig} and
 * {@link PermissiveSecurityConfig} are each conditional on {@code stockflow.security.enabled}, so a
 * policy defined in one of them does not exist when the other is active. That is how the local
 * profile ended up with an empty source: no origin allowed, every cross-origin call from a
 * frontend on another port blocked by the browser, while the chain itself permitted everything.</p>
 *
 * <p>The origins are configuration ({@link CorsProperties}); the methods and headers stay here,
 * because they follow from what the API accepts and returns, not from where it is deployed.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    /**
     * The bean name, for injection by {@code @Qualifier}. Injecting by type alone fails at startup:
     * Spring MVC's {@code mvcHandlerMappingIntrospector} is a {@link CorsConfigurationSource} too.
     * It is also the name Spring Security's {@code cors()} looks for by default.
     */
    public static final String SOURCE = "corsConfigurationSource";

    @Bean(SOURCE)
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        // Patterns, never "*" together with credentials - CorsProperties refuses that at startup.
        config.setAllowedOriginPatterns(properties.allowedOriginPatterns());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Correlation-Id", "Accept-Language"));
        // Without exposing these, browser JavaScript cannot read them - so a client could not show
        // the correlation id on an error page, nor back off correctly on a 429.
        config.setExposedHeaders(List.of("X-Correlation-Id", "X-RateLimit-Limit",
                "X-RateLimit-Remaining", "Retry-After", "Idempotency-Replayed"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

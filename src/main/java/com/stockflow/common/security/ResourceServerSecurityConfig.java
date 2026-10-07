package com.stockflow.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Resource-server configuration. The application validates the JWT issued by the identity module.
 *
 * <p>The edge guards the perimeter, but every request is checked again — zero trust inside the
 * process, so a bug in one module cannot hand another module an unauthenticated request.</p>
 *
 * <h2>Two chains, and why</h2>
 *
 * <p>{@link #apiFilterChain} covers {@code /api/**} with a strict Content-Security-Policy.
 * {@link #docsAndProbesFilterChain} covers Swagger UI and the health probes with a policy that lets
 * a page actually load. One chain with {@code default-src 'none'} applied to everything renders
 * Swagger UI as a blank page — the browser refuses every script, stylesheet and font the bundle
 * needs, and the endpoint explorer this project ships is silently useless.</p>
 *
 * <h2>Error responses go through our own handlers</h2>
 *
 * <p>{@link ApiAuthenticationEntryPoint} and {@link ApiAccessDeniedHandler} are wired in because the
 * filter chain runs <b>outside</b> Spring MVC: {@code @RestControllerAdvice} never sees a 401 or 403
 * raised here, and the default is an empty body. Without them a client needs a special case for
 * exactly the two status codes it hits most often.</p>
 *
 * <p>They are registered twice on the API chain. {@code exceptionHandling(...)} sets the chain-wide
 * default; {@code oauth2ResourceServer(...)} overrides the one
 * {@code BearerTokenAuthenticationFilter} installs for itself, which would otherwise win for an
 * expired or malformed token — the most common 401 there is.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity     // required for @PreAuthorize to be honoured at all
@ConditionalOnProperty(prefix = "stockflow.security", name = "enabled", havingValue = "true")
public class ResourceServerSecurityConfig {

    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;

    public ResourceServerSecurityConfig(ApiAuthenticationEntryPoint authenticationEntryPoint,
                                        ApiAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * Documentation and the container probes. Ordered first so its matcher wins.
     *
     * <p>Only the two <b>probe groups</b> are anonymous, not the aggregate {@code /actuator/health}.
     * The aggregate runs every health indicator, including {@code ObjectStorageHealthIndicator},
     * which issues a real request to S3 — and {@code /actuator/**} is exempt from rate limiting. An
     * anonymous caller in a {@code while true} loop would otherwise drive unbounded S3 traffic, with
     * the cost and the throttling landing on the real upload path.</p>
     */
    @Bean
    @Order(1)
    public SecurityFilterChain docsAndProbesFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/actuator/health/liveness", "/actuator/health/readiness",
                        "/actuator/info", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers
                        .contentTypeOptions(options -> { })
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        // Swagger UI is a real page: it needs its own scripts and styles, and the
                        // bundle uses inline initialisers. 'self' keeps it to assets this
                        // application serves, which is as tight as this page can be.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                                + "font-src 'self'; frame-ancestors 'none'")))
                .build();
    }

    /**
     * The decoder is declared here, rather than left to Boot's auto-configuration, so a token is
     * also refused once its session has ended ({@link SessionValidator}). Signature, {@code exp}
     * and {@code nbf} are still checked first by the default validators.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @org.springframework.beans.factory.annotation.Value(
                    "${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            ActiveSessionCheck sessions) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new SessionValidator(sessions)));
        return decoder;
    }

    /** Roles from the token, permissions from the server-side lookup (ADR-0008). */
    @Bean
    public StockflowJwtAuthenticationConverter stockflowJwtAuthenticationConverter(
            RoleAuthorizationLookup lookup) {
        return new StockflowJwtAuthenticationConverter(lookup);
    }

    /** Everything else: the API itself, plus the rest of actuator. CORS policy: {@link CorsConfig}. */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
            @Qualifier(CorsConfig.SOURCE) CorsConfigurationSource cors,
            StockflowJwtAuthenticationConverter converter) throws Exception {
        return http
                // Stateless JWT: there is no session cookie for an attacker to ride, so CSRF
                // protection buys nothing and only breaks the API clients.
                .csrf(csrf -> csrf.disable())
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(this::applyApiSecurityHeaders)
                .authorizeHttpRequests(auth -> auth
                        // No HttpMethod restriction. Scoping the admin authority to GET would leave
                        // every other method falling through to anyRequest().authenticated() - so
                        // any holder of any valid token could POST to /actuator/loggers or
                        // /actuator/shutdown the moment either is exposed. The local profile
                        // already exposes "*".
                        .requestMatchers("/actuator/**").hasAuthority(Roles.ECOMMERCE_ADMIN)
                        // No token exists yet at either of these: /auth/login is what mints one,
                        // and /oauth2/jwks is what THIS filter's own JwtDecoder fetches to validate
                        // it. Both are unauthenticated by necessity, not by oversight.
                        .requestMatchers("/api/v1/identity/auth/login", "/api/v1/customers/registrations",
                                "/api/v1/orders/guest-checkout", "/oauth2/jwks",
                                "/api/v1/public/products/**", "/api/v1/public/catalog/products/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .withObjectPostProcessor(reportUnavailableAsServiceError()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Routes an {@code AuthenticationServiceException} — "could not check", not "not valid" — to the
     * entry point, which answers it with a 503 envelope.
     *
     * <p>Spring Security 6 rethrows that exception type from the bearer-token filter by default. It
     * then escapes the chain as a servlet exception and the container renders its own 500 page,
     * outside the platform's envelope and without a correlation id. The permission lookup
     * (ADR-0008), the session check and the key-set fetch all fail this way when a backing store is
     * down.</p>
     */
    private ObjectPostProcessor<BearerTokenAuthenticationFilter> reportUnavailableAsServiceError() {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                AuthenticationEntryPointFailureHandler failureHandler =
                        new AuthenticationEntryPointFailureHandler(authenticationEntryPoint);
                failureHandler.setRethrowAuthenticationServiceException(false);
                filter.setAuthenticationFailureHandler(failureHandler);
                return filter;
            }
        };
    }

    /**
     * The response headers a browser needs in order to protect the user from our own mistakes.
     *
     * <ul>
     *   <li><b>Content-Security-Policy.</b> This chain serves JSON and nothing else, so it should
     *       never execute a script or load a frame. {@code default-src 'none'} means that even if a
     *       reflected value ends up rendered as HTML, the browser refuses to run it.</li>
     *   <li><b>X-Content-Type-Options: nosniff.</b> Without it a browser may ignore our
     *       {@code Content-Type} and guess from the bytes; an uploaded file echoed back can then be
     *       sniffed as HTML and executed. This is what makes the {@code ContentTypePolicy}
     *       allow-list hold at the browser rather than only at the server.</li>
     *   <li><b>HSTS.</b> After the first visit the browser refuses plain HTTP to this host, so a
     *       user on a hostile network cannot be downgraded and have their bearer token read.</li>
     *   <li><b>Referrer-Policy.</b> Stops full URLs — which carry ids and occasionally tokens —
     *       leaking to third parties in the {@code Referer} header.</li>
     * </ul>
     *
     * <p>HSTS is unconditional, which is correct behind a TLS-terminating proxy. Serving this header
     * from a real domain over plain HTTP would lock browsers out until the max-age expired — a
     * reason to keep local development on {@code localhost}, which browsers exempt.</p>
     */
    private void applyApiSecurityHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .contentTypeOptions(options -> { })
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000))       // one year, the value browsers expect
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
    }
}

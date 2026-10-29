package com.stockflow.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Hard enforcement of the forced password change (SCRUM-474 FIRST-LOGIN, decision 29/10/2026).
 *
 * <p>While the signed-in account still has the "must change password" flag, every API call is answered
 * {@code 403 PASSWORD_CHANGE_REQUIRED} except the four a client needs in order to get out of that state:
 * read who you are, read your permissions, change the password, and sign out. Nothing else is allowed,
 * so a stolen temporary password cannot be used to do anything but replace itself.</p>
 *
 * <p>It runs <b>after</b> {@code BearerTokenAuthenticationFilter}: only a request that already carries a
 * valid token and live session is looked at, and an anonymous request is left to the rules that
 * govern it. It is not a Spring bean on purpose - a {@code OncePerRequestFilter} bean would also be
 * registered with the servlet container, outside the security chain, where there is no authentication
 * to judge. The chain builds it, in {@link ResourceServerSecurityConfig}.</p>
 *
 * <p>The decision reads the flag from the database on every request ({@link PasswordChangeRequirement}),
 * not from the token: the token is issued before the change and is kept after it, so a claim would be
 * stale in both directions.</p>
 *
 * <p>If the flag cannot be read the request is refused with the same retryable 503 as any other
 * authorisation question nobody could answer - never let through, never reported as a sign-out.</p>
 */
public final class PasswordChangeGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeGateFilter.class);

    /** "METHOD path" of every call a forced user may still make. Exact match, no wildcards. */
    static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/identity/me",
            "GET /api/v1/identity/me/permissions",
            "PUT /api/v1/identity/auth/password",
            "POST /api/v1/identity/auth/logout",
            // Anonymous by design: a stale Authorization header sent with it must not turn signing in
            // as someone else into a 403. It is not an API call made on behalf of the forced user.
            "POST /api/v1/identity/auth/login");

    private final PasswordChangeRequirement requirement;
    private final AuthenticationEntryPoint unavailableHandler;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public PasswordChangeGateFilter(PasswordChangeRequirement requirement,
                                    AuthenticationEntryPoint unavailableHandler,
                                    ObjectMapper objectMapper, Messages messages) {
        this.requirement = requirement;
        this.unavailableHandler = unavailableHandler;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken) || !authentication.isAuthenticated()
                || isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId = userId(authentication);
        if (userId == null) {
            chain.doFilter(request, response);   // not one of our accounts: nothing to force
            return;
        }
        boolean required;
        try {
            required = requirement.isRequired(userId);
        } catch (RuntimeException unavailable) {
            unavailableHandler.commence(request, response,
                    new AuthenticationServiceException("The password-change flag is unavailable", unavailable));
            return;
        }
        if (!required) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("PASSWORD CHANGE REQUIRED: refused {} {} for user {}",
                LogSafe.text(request.getMethod()), LogSafe.text(request.getRequestURI()), userId);
        response.setStatus(ErrorCode.PASSWORD_CHANGE_REQUIRED.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(
                ErrorCode.PASSWORD_CHANGE_REQUIRED.name(),
                messages.forCode(ErrorCode.PASSWORD_CHANGE_REQUIRED),
                MDC.get(CorrelationIdFilter.MDC_KEY))));
    }

    /** CORS preflights carry no credentials and are answered before this filter would matter. */
    static boolean isAllowed(HttpServletRequest request) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return ALLOWED.contains(request.getMethod() + " " + path);
    }

    private static UUID userId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}

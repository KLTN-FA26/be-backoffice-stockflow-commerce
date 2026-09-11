package com.stockflow.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 401 in the platform's envelope instead of Spring Security's default.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@code GlobalExceptionHandler} covers everything thrown from a controller. It does <b>not</b>
 * cover 401 and 403 raised by the security filter chain, because those happen before Spring MVC is
 * involved — there is no handler, no {@code @ControllerAdvice}, nothing for the advice to wrap. The
 * default is an empty body with a {@code WWW-Authenticate} header.</p>
 *
 * <p>So a client that has one response handler for every endpoint suddenly has to special-case two
 * status codes: parse the envelope normally, except for 401 and 403, where there is nothing to
 * parse. Every client then reimplements that exception, and one of them gets it wrong and shows a
 * blank error dialog on session expiry — the single most common moment a user hits a 401.</p>
 *
 * <h2>What is deliberately not said</h2>
 *
 * <p>The message is the generic translated one. Spring's {@code AuthenticationException} says
 * whether the token was expired, malformed, or signed by the wrong key; telling an unauthenticated
 * caller which of those it was is telling an attacker how close they are.</p>
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final Messages messages;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper, Messages messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // The real reason goes to the log, where an operator can see it, and not to the caller.
        log.warn("Unauthenticated request to {} {}: {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());

        response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        // RFC 6750 requires a 401 to say how to authenticate. Overriding Spring's
        // BearerTokenAuthenticationEntryPoint drops the header it would have written, and a client
        // library that inspects it to decide whether to refresh a token would stop refreshing.
        // Deliberately without an error= parameter: that would say WHY the token was rejected.
        response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(
                        ErrorCode.UNAUTHORIZED.name(),
                        messages.forCode(ErrorCode.UNAUTHORIZED),
                        MDC.get(CorrelationIdFilter.MDC_KEY))));
    }
}

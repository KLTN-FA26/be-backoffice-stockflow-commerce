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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 403 in the platform's envelope instead of Spring Security's default.
 *
 * <p>The counterpart to {@link ApiAuthenticationEntryPoint}: that one handles "we do not know who
 * you are", this one handles "we do, and you may not". Both exist because the security filter chain
 * runs outside Spring MVC, so {@code @RestControllerAdvice} never sees these.</p>
 *
 * <p>Logged at {@code WARN} and tagged so it can be filtered: an authenticated user being refused
 * repeatedly is a different signal from an anonymous one being refused. The first is either a
 * misconfigured role or somebody exploring what they can reach.</p>
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;
    private final Messages messages;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper, Messages messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("AUTHORISATION DENIED by the filter chain: {} {} - {}",
                request.getMethod(), request.getRequestURI(), accessDeniedException.getMessage());

        response.setStatus(ErrorCode.FORBIDDEN.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(
                        ErrorCode.FORBIDDEN.name(),
                        messages.forCode(ErrorCode.FORBIDDEN),
                        MDC.get(CorrelationIdFilter.MDC_KEY))));
    }
}

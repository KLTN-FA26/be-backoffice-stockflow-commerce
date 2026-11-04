package com.stockflow.assistant.internal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.service.McpTokens;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.CorsConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The MCP transport's defence against DNS rebinding (2025-11-25, Streamable HTTP): a request to the MCP endpoint whose
 * {@code Origin} the shared CORS policy does not allow is answered 403 with a JSON-RPC error and no id, before
 * anything else looks at it.
 *
 * <p><b>Before authentication, on purpose.</b> The transport requires the 403 whenever an Origin is present and not
 * allowed, token or not, so this runs ahead of Spring Security ({@value #ORDER}, one before its chain): a page with no
 * token is refused with 403 rather than 401, and a refused request spends no rate limit. The CORS filter refuses a
 * cross-origin request too, but only one whose Origin differs from the Host it calls, and with a plain-text body; a
 * page whose host name was made to resolve to this server sends an Origin matching that Host, which only this check
 * sees.</p>
 *
 * <p>A client that is not a browser (Claude Code, Claude Desktop through mcp-remote, the MCP Inspector's proxy) sends
 * no Origin and passes. A preflight ({@code OPTIONS}) is left to the CORS filter, which answers it.</p>
 */
@Component
@Order(McpOriginFilter.ORDER)
@ConditionalOnProperty(name = McpTokens.ENABLED, havingValue = "true")
class McpOriginFilter extends OncePerRequestFilter {

    /** One before Spring Security's chain. */
    static final int ORDER = SecurityProperties.DEFAULT_FILTER_ORDER - 1;

    private static final Logger log = LoggerFactory.getLogger(McpOriginFilter.class);

    private final CorsConfigurationSource cors;
    private final ObjectMapper json;

    McpOriginFilter(@Qualifier(CorsConfig.SOURCE) CorsConfigurationSource cors, ObjectMapper json) {
        this.cors = cors;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !McpTokens.MCP_PATH.equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || allowed(origin, request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("MCP request refused: Origin {} is not allowed", LogSafe.text(origin));
        McpServer.Reply refused = McpServer.originRefused();
        response.setStatus(refused.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), refused.body());
    }

    /** Whether the CORS policy allows {@code origin}: the same list the browser's preflight is answered from. */
    private boolean allowed(String origin, HttpServletRequest request) {
        CorsConfiguration policy = cors.getCorsConfiguration(request);
        return policy != null && policy.checkOrigin(origin) != null;
    }
}

package com.stockflow.common.web;

import com.stockflow.common.security.DataScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id, puts it in the MDC and echoes it back on the response.
 *
 * <p>The id ties together every log line produced while serving one request. Micrometer Tracing
 * supplies {@code traceId}/{@code spanId} as well, and they overlap — the difference is that a
 * correlation id can be <b>chosen by the caller</b>, so a mobile client can report "request
 * abc-123 failed" and support can find it without a trace UI.</p>
 *
 * <h2>The caller's id is not trusted verbatim</h2>
 *
 * <p>It goes into log lines, so an unvalidated value is a log-injection vector: a header containing
 * a newline can forge a whole log entry, and a 10 KB header can bloat every line of a request.
 * {@link #sanitise} keeps it to a bounded set of characters and a bounded length, and falls back to
 * a generated id when the supplied one does not survive that.</p>
 *
 * <h2>Also the backstop for the data-scope thread-local</h2>
 *
 * <p>{@code RequiresPermissionAspect} clears the scope in its own {@code finally}. This filter
 * clears it again on the way out, which covers the paths the aspect never sees: a request rejected
 * by a servlet filter before the controller, an exception thrown by another aspect ordered outside
 * it, or a future code path that sets the scope without going through the annotation. A leaked
 * scope is one user seeing another user's rows, so it is worth two independent clears.</p>
 */
@Component
// HIGHEST_PRECEDENCE, not 1. Spring Security's chain registers at SecurityProperties
// .DEFAULT_FILTER_ORDER == -100, so any positive order puts this filter AFTER it - and then a 401
// or 403 raised by the security chain is written by ApiAuthenticationEntryPoint with an EMPTY MDC:
// no correlation id in the body, no X-Correlation-Id header, and a log line that cannot be joined
// to the response the user is looking at. Those two statuses are precisely what those handlers
// exist to make traceable.
@Order(Ordered.HIGHEST_PRECEDENCE)
// ROLE_INFRASTRUCTURE + final: see IdempotencyFilter's javadoc for the full explanation.
// Short version: Spring Modulith's ModuleObservabilityBeanPostProcessor treats every subpackage
// under the base package (including common) as an implicit module and tries to wrap its beans for
// tracing; ROLE_INFRASTRUCTURE is the documented opt-out. `final` is a second, independent guard —
// OncePerRequestFilter's final methods can never be correctly proxied by CGLIB regardless.
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Enough for a UUID, a ULID or a short client tag; short enough not to bloat every log line. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            DataScopeContext.clear();
        }
    }

    /**
     * Accepts only characters that are safe in a log line and a response header, and rejects
     * anything too long. Returns a fresh id when the supplied value is absent or unusable, so the
     * rest of the request never has to handle a missing correlation id.
     */
    static String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':';
            if (!safe) {
                return UUID.randomUUID().toString();
            }
        }
        return supplied;
    }
}

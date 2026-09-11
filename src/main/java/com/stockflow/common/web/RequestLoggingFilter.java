package com.stockflow.common.web;

import com.stockflow.common.security.CurrentUserProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * One log line per request: method, path, status, duration, user.
 *
 * <p>The line that answers "what actually happened at 14:32" without a tracing UI, and the input to
 * every "which endpoint got slow" question. Paired with {@link CorrelationIdFilter}, whose id is
 * already in the MDC by the time this runs, so the line joins up with everything the request
 * logged along the way.</p>
 *
 * <h2>What is deliberately not logged</h2>
 *
 * <ul>
 *   <li><b>Request and response bodies.</b> They contain customer addresses, prices and
 *       occasionally credentials, and logs travel further than databases do — they are shipped,
 *       indexed and kept for months, usually with wider access than the data itself. Bodies belong
 *       in a trace with sampling and redaction, not in an application log.</li>
 *   <li><b>The query string, unfiltered.</b> Tokens and search terms end up there. Only the path is
 *       logged; {@link #QUERY_ALLOW_LIST} names the few parameters worth keeping.</li>
 *   <li><b>Authorization and Cookie headers.</b> Never, under any log level.</li>
 * </ul>
 *
 * <p>Health probes are skipped entirely — at one probe per second they would be most of the log
 * volume and none of its value.</p>
 */
@Component
@Order(10)
@ConditionalOnProperty(prefix = "stockflow.web.request-log", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** Parameters safe to keep: they shape the query, and none of them carries user content. */
    private static final Set<String> QUERY_ALLOW_LIST = Set.of("page", "size", "sort", "status");

    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/actuator/health", "/actuator/prometheus", "/swagger-ui", "/v3/api-docs");

    /** Requests slower than this are logged at WARN so they surface without a dashboard. */
    private static final long SLOW_REQUEST_MILLIS = 1_000;

    private final CurrentUserProvider currentUserProvider;

    public RequestLoggingFilter(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return SKIP_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Nothing in here may throw. This is a finally block in a filter: an exception raised
            // here replaces the response the application was about to send, escapes
            // OncePerRequestFilter, and is never seen by @RestControllerAdvice - the client gets
            // the container's HTML error page, or a truncated body if the response was already
            // committed. Losing a log line is a far smaller problem than losing the response, so
            // the whole block is guarded.
            try {
                logCompletion(request, response, startedAt);
            } catch (RuntimeException loggingFailure) {
                log.warn("Failed to log request completion", loggingFailure);
            }
        }
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response,
                               long startedAt) {
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        int status = response.getStatus();
        // Resolved after the chain: authentication happens inside it, so asking before would
        // report every request as anonymous.
        String user = currentUserProvider.current()
                .map(u -> u.username() == null ? String.valueOf(u.userId()) : u.username())
                .orElse("-");

        String message = "{} {}{} -> {} in {}ms [user={}]";
        Object[] args = {request.getMethod(), request.getRequestURI(),
                safeQuery(request), status, millis, user};

        if (status >= 500) {
            log.error(message, args);
        } else if (status >= 400 || millis >= SLOW_REQUEST_MILLIS) {
            log.warn(message, args);
        } else {
            log.info(message, args);
        }
    }

    /**
     * Rebuilds the query string from allow-listed parameters only.
     *
     * <p>An allow-list rather than a deny-list of known-sensitive names: a deny-list is a promise
     * to remember every future parameter that turns out to carry a secret, and that promise is
     * always eventually broken.</p>
     */
    private static String safeQuery(HttpServletRequest request) {
        String raw = request.getQueryString();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (QUERY_ALLOW_LIST.contains(name)) {
                kept.append(kept.isEmpty() ? "?" : "&").append(pair);
            }
        }
        return kept.toString();
    }
}

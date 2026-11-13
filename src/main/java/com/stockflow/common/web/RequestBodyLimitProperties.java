package com.stockflow.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/**
 * Per-route caps on a request body, bound from {@code stockflow.web.body-limits}. Read by {@link RequestBodyLimitFilter}.
 *
 * <p>Configuration rather than an annotation on the handler, because the cap has to bite before anything reads the
 * body - the idempotency filter buffers it to fingerprint the request, and Jackson materialises the whole tree - and
 * both run before a handler is known. A route listed here is a route whose body is meant to be large but not
 * unbounded: a customer's design document is one (kltn-docs 12; 1,000,000 bytes, which the database checks too).</p>
 *
 * @param bodyLimits the capped routes; a request matching none is not capped here (the container's own limits apply)
 */
@ConfigurationProperties(prefix = "stockflow.web")
public record RequestBodyLimitProperties(@DefaultValue List<Limit> bodyLimits) {

    public RequestBodyLimitProperties {
        bodyLimits = bodyLimits == null ? List.of() : List.copyOf(bodyLimits);
    }

    /**
     * One capped route.
     *
     * @param method   the HTTP method, case-insensitive
     * @param pattern  a Spring path pattern over the request path, e.g. {@code /api/v1/designs/{id}/document}
     * @param maxBytes the largest body accepted, in bytes
     */
    public record Limit(String method, String pattern, long maxBytes) {

        public Limit {
            if (method == null || method.isBlank() || pattern == null || !pattern.startsWith("/")) {
                throw new IllegalArgumentException("A body limit needs a method and a path pattern starting with /");
            }
            if (maxBytes < 1) {
                throw new IllegalArgumentException("A body limit must be at least one byte");
            }
            method = method.toUpperCase(Locale.ROOT);
        }
    }
}

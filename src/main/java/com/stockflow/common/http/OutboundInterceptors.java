package com.stockflow.common.http;

import com.stockflow.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * The two interceptors every outbound client gets.
 *
 * <p>Kept together because they are the pair that makes an outbound call debuggable at all: one
 * carries the request's identity onward, the other records what happened.</p>
 */
public final class OutboundInterceptors {

    private static final Logger log = LoggerFactory.getLogger(OutboundInterceptors.class);

    private OutboundInterceptors() {
    }

    /**
     * Passes the correlation id to whatever we call.
     *
     * <p>Without it the trail stops at our boundary: a failure in a supplier's system cannot be
     * matched to the order that triggered it, and the supplier's support team has nothing to search
     * on. One header, and "which of your calls was it" becomes answerable.</p>
     */
    public static ClientHttpRequestInterceptor propagateCorrelationId() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().add(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        };
    }

    /**
     * One log line per outbound call: method, URI, status, duration.
     *
     * <p>The URI is logged without its query string. Outbound URLs carry API keys and tokens as
     * parameters far more often than inbound ones do, and a log line is a much easier place to read
     * a secret from than a database.</p>
     *
     * <p>Failures are logged and rethrown — never swallowed. An interceptor that hides a connection
     * failure produces a null response that the caller then dereferences, and the stack trace
     * points at the wrong place entirely.</p>
     */
    public static ClientHttpRequestInterceptor logCalls(String serviceName) {
        return (request, body, execution) -> {
            long startedAt = System.nanoTime();
            String target = request.getURI().getPath();
            try {
                var response = execution.execute(request, body);
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                int status = response.getRawStatusCode();
                if (status >= 400) {
                    log.warn("-> {} {} {} returned {} in {}ms",
                            serviceName, request.getMethod(), target, status, millis);
                } else {
                    log.debug("-> {} {} {} returned {} in {}ms",
                            serviceName, request.getMethod(), target, status, millis);
                }
                return response;
            } catch (java.io.IOException | RuntimeException failure) {
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                log.warn("-> {} {} {} failed after {}ms: {}",
                        serviceName, request.getMethod(), target, millis, failure.toString());
                throw failure;
            }
        };
    }
}

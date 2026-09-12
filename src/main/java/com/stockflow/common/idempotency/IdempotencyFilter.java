package com.stockflow.common.idempotency;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Makes any unsafe request carrying an {@code Idempotency-Key} header safe to retry.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li>No header, or a safe method (GET/HEAD/OPTIONS) — does nothing at all.</li>
 *   <li>Claims the key atomically. First arrival wins and proceeds.</li>
 *   <li>A duplicate whose original <b>completed</b> gets the stored status and body back, byte for
 *       byte, and the real handler never runs. That is the whole point: a retried checkout returns
 *       the original order rather than creating a second one.</li>
 *   <li>A duplicate whose original is <b>still running</b> gets 409 and is expected to retry
 *       shortly. It cannot be answered yet, and running it would defeat the mechanism.</li>
 *   <li>A duplicate with a <b>different body</b> gets 422. The key was reused for something else,
 *       and replaying the first response would report success for work that never happened.</li>
 * </ol>
 *
 * <h2>Interaction with the application's own idempotency</h2>
 *
 * <p>This is HTTP-level and complements, rather than replaces, the domain-level keys —
 * {@code PlaceOrderCommand.requestId}, {@code ReserveStockCommand.requestId}. The two catch
 * different retries: this one catches a client resending the same HTTP request; the domain one
 * catches a caller reaching the service by any route, including from another module in-process.
 * Removing either leaves a real hole.</p>
 *
 * <h2>Ordering, and why the response is buffered</h2>
 *
 * <p>{@code @Order(20)} puts it after {@code CorrelationIdFilter} (so replays are traceable) and
 * before the controller. The response is wrapped so its bytes can be stored — which means the body
 * is held in heap and written out at the end. Acceptable for JSON API responses; the filter skips
 * multipart requests entirely so a file upload is never buffered on either side.</p>
 *
 * <p>Only <i>settled</i> outcomes are stored — see {@link #isStorable(int)}. Anything that means
 * "try again" (5xx, 429, 409, 408) releases the claim instead, so the retry the response asked for
 * is actually possible. Storing one of those and replaying it for the retention window would turn a
 * momentary condition into a permanent one for that key.</p>
 */
@Component
@Order(20)
@ConditionalOnProperty(prefix = "stockflow.idempotency", name = "enabled",
        havingValue = "true", matchIfMissing = true)
// ROLE_INFRASTRUCTURE: every direct subpackage of the base package is an implicit Spring Modulith
// module, including common - so ModuleObservabilityBeanPostProcessor (OTLP tracing spans per
// module boundary) tries to wrap this bean too. Its own isInfrastructureBean(...) check is the
// documented way to opt out, and it is load-bearing here, not cosmetic: this class extends
// OncePerRequestFilter, whose doFilter()/init() are final in GenericFilterBean, so a CGLIB
// subclass proxy could never override them anyway - the attempt fails at bean creation with
// Tomcat unable to start ("Cannot subclass final class", once `final` below forces a loud error
// instead of the silent one) or, without `final`, an Objenesis-instantiated shell whose inherited
// `logger` field was never set, NPEing on the first request. `final` stays as a second,
// independent guard: even if this bean were ever pulled into a real advisor's pointcut for a
// legitimate reason, a final class still cannot be CGLIB-subclassed, so the failure would be loud
// immediately rather than a silent NPE in production.
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public final class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    /** Methods that change state. GET and friends need no protection - they are already idempotent. */
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * How long a stored response can be replayed.
     *
     * <p>24 hours is the industry convention and it is a balance: long enough to cover a client
     * retrying after an outage or a queue redelivering overnight, short enough that the table stays
     * small and a key can eventually be reused for something new.</p>
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyStore store;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyStore store, CurrentUserProvider currentUserProvider,
                             ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (request.getHeader(IdempotencyKeys.HEADER) == null) {
            return true;
        }
        String contentType = request.getHeader("Content-Type");
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        // Multipart: buffering the body would hold an entire upload in heap, twice. An idempotent
        // upload needs a different fingerprint strategy, not this filter.
        //
        // Form-encoded: the servlet container parses the body to answer getParameter(), consuming
        // the stream. CachedBodyRequest replays getInputStream()/getReader() but does NOT override
        // the four getParameter* methods, which HttpServletRequestWrapper delegates to the original
        // request - whose stream this filter has already read and closed. A controller would see
        // every @RequestParam bound from the body come back null, and the failure would look like a
        // binding bug rather than a filter bug. This API is JSON, so skipping is the honest answer;
        // supporting it means overriding the parameter methods, not relaxing this check.
        return type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key;
        try {
            key = IdempotencyKeys.validate(request.getHeader(IdempotencyKeys.HEADER));
        } catch (BusinessException ex) {
            writeError(response, ex.errorCode(), ex.getMessage());
            return;
        }

        CachedBodyRequest buffered = CachedBodyRequest.wrap(request);
        String caller = IdempotencyKeys.callerNamespace(
                currentUserProvider.current().map(u -> u.userId()).orElse(null));
        String fingerprint = IdempotencyKeys.fingerprint(
                request.getMethod(), pathWithQuery(request), buffered.body());

        Instant now = clock.instant();
        Optional<IdempotencyRecord> existing =
                store.beginIfAbsent(key, caller, fingerprint, now, now.plus(RETENTION));

        if (existing.isPresent()) {
            replayOrReject(existing.get(), key, fingerprint, response);
            return;
        }

        // We hold the claim. Run the real request, buffering the response so it can be stored.
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(buffered, captured);

            int status = captured.getStatus();
            if (isStorable(status)) {
                String body = new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8);
                store.complete(key, caller, status, body, clock.instant());
                stored = true;
            }
        } finally {
            if (!stored) {
                // Covers both the 5xx path and an exception propagating out of the chain. Without
                // this, the IN_PROGRESS claim survives and every retry gets 409 until it expires.
                store.abandon(key, caller);
            }
            captured.copyBodyToResponse();
        }
    }

    /**
     * Whether a response is a final answer worth replaying for the whole retention window.
     *
     * <h2>Why "not a 5xx" is not enough</h2>
     *
     * <p>Storing a response means every retry with that key gets it back <b>without reaching the
     * controller</b>, for 24 hours. That is right for a settled outcome — 201 Created, or a 422 the
     * same input will always produce. It is actively harmful for a response that means "not now,
     * try again", because the retry the response asked for is the very thing the store prevents.</p>
     *
     * <p>The concrete failure: a client POSTs an order, happens to trip the rate limiter, and gets
     * 429 with {@code Retry-After: 12}. Under a plain {@code status < 500} rule that 429 is stored.
     * Twelve seconds later the client does as it was told and retries with the same key — and is
     * handed the cached 429 back, forever. The key is poisoned and the order can never be placed.
     * Note that the rate limiter runs as a Spring MVC interceptor, i.e. <i>inside</i> this filter,
     * so its 429 passes through here like any controller response.</p>
     *
     * <p>The statuses below are the ones this application can emit that carry "retry" as their
     * meaning. {@link com.stockflow.common.error.ErrorCode#isRetryable()} is the domain-level
     * counterpart of the same list — {@code RATE_LIMITED} (429), {@code OPTIMISTIC_LOCK} and
     * {@code LOCK_TIMEOUT} (409) all map into it. Re-running a retryable request is safe by
     * construction: it is still guarded by the claim, so only one attempt runs at a time.</p>
     *
     * <p>5xx is excluded for the same reason plus one more — it is usually transient, and replaying
     * a blip for 24 hours turns it into an outage the client cannot escape.</p>
     */
    private static boolean isStorable(int status) {
        if (status >= 500) {
            return false;
        }
        return switch (status) {
            case 408,   // Request Timeout
                 409,   // Conflict - optimistic lock and lock timeout both land here
                 423,   // Locked
                 425,   // Too Early
                 429    // Too Many Requests
                    -> false;
            default -> true;
        };
    }

    private void replayOrReject(IdempotencyRecord record, String key, String fingerprint,
                                HttpServletResponse response) throws IOException {
        if (!record.matches(fingerprint)) {
            log.warn("Idempotency key {} reused for a different request", key);
            writeError(response, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    ErrorCode.IDEMPOTENCY_KEY_REUSED.defaultMessage());
            return;
        }
        if (!record.isReplayable()) {
            log.info("Idempotency key {} is still being processed", key);
            // Tells a well-behaved client to wait rather than hammer.
            response.setHeader("Retry-After", "1");
            writeError(response, ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS,
                    ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS.defaultMessage());
            return;
        }

        log.info("Replaying stored response for idempotency key {}", key);
        response.setStatus(record.responseStatus());
        response.setContentType("application/json");
        // Marks the response as a replay, so a client - and anyone reading a HAR file - can tell
        // that no new work was done. Without it a replayed 201 looks like a second creation.
        response.setHeader("Idempotency-Replayed", "true");
        response.getWriter().write(record.responseBody() == null ? "" : record.responseBody());
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType("application/json");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(code.name(), message)));
    }

    private static String pathWithQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}

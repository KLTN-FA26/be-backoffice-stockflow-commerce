package com.stockflow.common.ratelimit;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Applies {@link RateLimit} to the endpoint about to run.
 *
 * <p>An interceptor rather than a filter, because the decision needs the {@link HandlerMethod} —
 * a filter runs before Spring MVC has resolved which method will handle the request, so it cannot
 * read the annotation. It also runs after authentication, so {@link RateLimit.Key#USER} has a user
 * to key on.</p>
 *
 * <p>Denial is thrown as a {@link BusinessException}, not written directly, so it flows through
 * {@code GlobalExceptionHandler} and comes back in the same envelope as every other error. A
 * hand-written 429 body here would be the one response shape clients have to special-case.</p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private final RateLimiter rateLimiter;
    private final CurrentUserProvider currentUserProvider;

    public RateLimitInterceptor(RateLimiter rateLimiter, CurrentUserProvider currentUserProvider) {
        this.rateLimiter = rateLimiter;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;   // static resources, error dispatch
        }
        RateLimit limit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (limit == null) {
            return true;
        }

        String bucket = limit.bucket().isBlank()
                ? handlerMethod.getMethod().getDeclaringClass().getSimpleName()
                        + "." + handlerMethod.getMethod().getName()
                : limit.bucket();
        String identity = identityFor(limit.key(), request);

        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                bucket + ":" + identity, limit.limit(), Duration.ofSeconds(limit.perSeconds()));

        response.setHeader(HEADER_LIMIT, String.valueOf(limit.limit()));
        if (decision.remaining() >= 0) {
            response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
        }

        if (!decision.allowed()) {
            // Seconds, rounded up: Retry-After has no sub-second form, and rounding down would
            // invite a client to retry a moment too early and be refused again.
            long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
            log.info("Rate limit hit: bucket={} identity={} retryAfter={}s",
                    LogSafe.text(bucket), LogSafe.text(identity), retryAfterSeconds);
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many requests. Try again in %d second(s).".formatted(retryAfterSeconds));
        }
        return true;
    }

    private String identityFor(RateLimit.Key key, HttpServletRequest request) {
        if (key == RateLimit.Key.USER) {
            return currentUserProvider.current()
                    .map(user -> "u:" + user.userId())
                    // An anonymous caller on a USER-keyed endpoint still has to be limited by
                    // something. Falling back to the address is the only identity available, and
                    // it is better than a shared "anonymous" bucket that one client can drain for
                    // everybody.
                    .orElseGet(() -> "ip:" + clientAddress(request));
        }
        return "ip:" + clientAddress(request);
    }

    /**
     * The address the container reports for the caller, and nothing else.
     *
     * <p>{@code X-Forwarded-For} is deliberately NOT read here (SCRUM-483). It is client-supplied, so
     * reading it from any caller let one client send a different "address" per request and get a
     * fresh bucket every time, which defeats the login and activation limits. Whether a forwarded
     * header may be believed is decided once, by the servlet container: with
     * {@code server.forward-headers-strategy=native} (dev and prod profiles) Tomcat's remote-IP
     * valve rewrites {@link HttpServletRequest#getRemoteAddr()} from {@code X-Forwarded-For} only when
     * the connection comes from {@code server.tomcat.remoteip.internal-proxies}, and ignores the
     * header from anyone else. So this method is correct for both cases: behind the trusted proxy
     * it returns the real client, and from an untrusted caller it returns that caller's own
     * address.</p>
     */
    private static String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}

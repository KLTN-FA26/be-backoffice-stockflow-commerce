package com.stockflow.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Caps how often one caller may invoke this endpoint.
 *
 * <h2>What it is for, and what it is not</h2>
 *
 * <p>It protects <b>expensive or abusable</b> endpoints: login (credential stuffing), password
 * reset and OTP (SMS costs real money), search and export (each call is a heavy query), and public
 * endpoints that reach the database on every miss. It is not a substitute for authorisation, and
 * putting it on every endpoint would be noise — an authenticated user browsing their own orders is
 * not a threat.</p>
 *
 * <pre>
 * &#64;PostMapping("/auth/login")
 * &#64;RateLimit(limit = 5, perSeconds = 60, key = RateLimit.Key.IP)
 * ApiResponse&lt;TokenResponse&gt; login(...)
 * </pre>
 *
 * <h2>Choosing the key</h2>
 *
 * <p>{@link Key#IP} for anything unauthenticated — there is no user yet, and the attacker is a
 * source address. {@link Key#USER} for authenticated endpoints, so one user's export loop cannot
 * exhaust everyone else's budget. Getting this backwards is a real failure: keying login by user
 * lets an attacker lock a victim out by failing their login five times, which turns a protection
 * into a denial of service against the person it was meant to protect.</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** Requests allowed per window. Also the burst capacity - see {@link RateLimiter}. */
    int limit();

    /** Window length in seconds. Constants only, because annotation values must be compile-time. */
    int perSeconds() default 60;

    Key key() default Key.USER;

    /**
     * Optional bucket name. Endpoints sharing a name share a budget.
     *
     * <p>Useful when several endpoints hit the same expensive dependency — three SMS-sending
     * endpoints should share one budget, otherwise the real limit is three times what was
     * intended. Defaults to the method's own identity.</p>
     */
    String bucket() default "";

    enum Key {
        /** The authenticated user id. Falls back to the client address when anonymous. */
        USER,
        /** The client address. The right choice for anything reachable before authentication. */
        IP
    }
}

package com.stockflow.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Token bucket in Redis, evaluated by a Lua script.
 *
 * <h2>Why Lua and not a few Redis commands</h2>
 *
 * <p>Refilling a bucket is read-modify-write: read the token count and timestamp, compute the
 * refill, subtract one, write both back. Done as separate commands from the application, two
 * concurrent requests interleave and both see the same starting count — so a limit of 5 admits 6,
 * 7 or more under exactly the load the limit exists for. Redis runs a Lua script atomically, so the
 * whole read-modify-write is one indivisible step. Nothing else about this class is subtle; this
 * part is the reason it exists.</p>
 *
 * <h2>Clock</h2>
 *
 * <p>The timestamp is taken from the application, not from Redis's {@code TIME}. That keeps the
 * script deterministic — a requirement for older Redis replication modes — and it means the
 * injected {@link Clock} makes the refill behaviour testable without sleeping.</p>
 */
@Component
class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String KEY_PREFIX = "stockflow:ratelimit:";

    /**
     * Returns {@code {allowed, remaining, millisUntilNextToken}}.
     *
     * <p>Every value is passed in rather than read inside the script, so the script has no
     * dependency on Redis state beyond the bucket itself.</p>
     */
    @SuppressWarnings("rawtypes") // Redis returns a heterogeneous Lua table; the element types
                                  // are checked in toLong() rather than by the compiler.
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local key           = KEYS[1]
            local capacity      = tonumber(ARGV[1])
            local windowMillis  = tonumber(ARGV[2])
            local nowMillis     = tonumber(ARGV[3])
            local ttlMillis     = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts     = tonumber(bucket[2])

            if tokens == nil or ts == nil then
              tokens = capacity
              ts = nowMillis
            end

            -- Refill proportionally to elapsed time. max(0, ...) guards against a clock that went
            -- backwards, which would otherwise remove tokens the caller had already earned.
            local elapsed = math.max(0, nowMillis - ts)
            local refillPerMilli = capacity / windowMillis
            tokens = math.min(capacity, tokens + elapsed * refillPerMilli)

            local allowed = 0
            local retryAfterMillis = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              -- Time until the bucket holds one whole token again.
              retryAfterMillis = math.ceil((1 - tokens) / refillPerMilli)
            end

            redis.call('HSET', key, 'tokens', tokens, 'ts', nowMillis)
            redis.call('PEXPIRE', key, ttlMillis)

            return { allowed, math.floor(tokens), retryAfterMillis }
            """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String bucketKey, int capacity, Duration window) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate limit capacity must be at least 1");
        }
        long windowMillis = window.toMillis();
        if (windowMillis < 1) {
            throw new IllegalArgumentException("Rate limit window must be at least one millisecond");
        }

        // Expire an idle bucket after two windows, purely to stop abandoned keys accumulating.
        //
        // Note what this does NOT do: it does not stop a pausing client getting a full bucket. The
        // bucket refills at capacity/window per millisecond, so after one idle window it is back at
        // capacity anyway - deleting the key at 2x window and keeping it are behaviourally
        // identical for the client. Two windows simply gives a little margin over the refill period
        // before Redis reclaims the memory.
        long ttlMillis = windowMillis * 2;

        try {
            List<?> result = redis.execute(
                    TOKEN_BUCKET,
                    List.of(KEY_PREFIX + bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(windowMillis),
                    String.valueOf(clock.millis()),
                    String.valueOf(ttlMillis));

            if (result == null || result.size() < 3) {
                log.warn("Rate limiter script returned {}; failing open", result);
                return Decision.failOpen();
            }
            boolean allowed = toLong(result.get(0)) == 1L;
            int remaining = (int) toLong(result.get(1));
            long retryAfterMillis = toLong(result.get(2));

            return allowed
                    ? Decision.allowed(remaining)
                    : Decision.denied(Duration.ofMillis(retryAfterMillis));

        } catch (RuntimeException ex) {
            // Redis unreachable, script error, connection pool exhausted. See Decision.failOpen()
            // for why this allows the request rather than blocking it.
            log.warn("Rate limiter unavailable for bucket {} - allowing the request: {}",
                    bucketKey, ex.toString());
            return Decision.failOpen();
        }
    }

    /** Redis integers arrive as {@code Long}; a Lua number can also come back as a {@code String}. */
    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}

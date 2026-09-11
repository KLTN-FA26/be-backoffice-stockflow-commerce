package com.stockflow.common.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DistributedLock} on Redis, using {@code SET key token NX PX ttl}.
 *
 * <h2>The token, and the bug it prevents</h2>
 *
 * <p>Every acquisition writes a random token as the value, and release deletes the key <b>only if
 * the token still matches</b>. Without that check, this sequence loses mutual exclusion
 * entirely:</p>
 *
 * <pre>
 * A acquires "nightly-report", lease 30s
 * A stalls for 35 seconds (GC pause, slow query, suspended VM)
 * the lease expires; the key is gone
 * B acquires "nightly-report" and starts working
 * A wakes up and calls release -> DEL deletes B's lock
 * C acquires while B is still running -> two holders
 * </pre>
 *
 * <p>The compare-and-delete makes A's release a no-op, because the token in Redis is now B's. It
 * does <i>not</i> stop A and B overlapping in the first place — nothing can, see
 * {@link DistributedLock} — but it stops the damage from cascading to a third holder.</p>
 *
 * <p>Delete-if-matches has to be atomic, so it is a Lua script for the same reason the rate limiter
 * is: a {@code GET} followed by a {@code DEL} from the application can interleave with an expiry
 * between the two.</p>
 */
@Component
class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private static final String KEY_PREFIX = "stockflow:lock:";

    private static final RedisScript<Long> RELEASE_IF_MINE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Handle> tryAcquire(String name, Duration leaseTime) {
        if (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()) {
            // A lock with no lease is a lock that is never released when a process dies. Refusing
            // is better than handing out something that will eventually wedge the system.
            throw new IllegalArgumentException("A distributed lock needs a positive lease time");
        }
        String key = KEY_PREFIX + name;
        String token = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, leaseTime);
        } catch (RuntimeException ex) {
            // Fail CLOSED, unlike the rate limiter. Not acquiring means the guarded work is skipped
            // this round; assuming the lock was taken would let every instance run it at once,
            // which is the precise outcome the lock exists to prevent.
            log.warn("Could not reach Redis to acquire lock '{}' - treating it as held: {}",
                    name, ex.toString());
            return Optional.empty();
        }

        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        log.debug("Acquired lock '{}' for {}", name, leaseTime);
        return Optional.of(() -> release(name, key, token));
    }

    private void release(String name, String key, String token) {
        try {
            Long deleted = redis.execute(RELEASE_IF_MINE, List.of(key), token);
            if (deleted == null || deleted == 0L) {
                // The lease expired and somebody else may now hold it. Worth a warning: it means
                // the work took longer than the lease, and the lease should be raised.
                log.warn("Lock '{}' was no longer ours at release - the lease expired mid-work", name);
            }
        } catch (RuntimeException ex) {
            // Nothing useful to do. The lease will expire on its own, which is exactly why leases
            // exist; throwing here would mask whatever the guarded work was doing.
            log.warn("Failed to release lock '{}'; it will expire on its own: {}", name, ex.toString());
        }
    }
}

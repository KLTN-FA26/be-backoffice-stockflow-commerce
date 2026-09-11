package com.stockflow.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Keeps a Redis outage from becoming an application outage.
 *
 * <h2>The default behaviour, and why it is wrong here</h2>
 *
 * <p>Spring's default error handler rethrows. So if Redis is unreachable — a failover, a network
 * blip, a {@code maxmemory} rejection — every {@code @Cacheable} method throws, and the whole
 * application returns 500s. That is a cache taking down a system that would have worked perfectly
 * well by reading the database, which is the opposite of what a cache is for.</p>
 *
 * <p>This handler swallows read, write and evict failures and lets the call proceed to the real
 * method. The application degrades to "slower, correct" rather than "fast, down".</p>
 *
 * <h2>The one case that is not safe to swallow, and what to do about it</h2>
 *
 * <p>A failed <b>evict</b> is different in kind from a failed read. Swallowing it means a stale
 * entry survives its intended invalidation, and keeps being served until the TTL expires — so a
 * price change or a permission revocation silently does not take effect. It is still swallowed
 * here, because failing the user's write to report a cache problem is worse, but it is logged at
 * {@code ERROR} with the cache and key named, and that log line is worth an alert. Bounded TTLs on
 * every cache (see {@link CacheNames}) are what stop this from lasting forever; that is the second
 * reason nothing here is configured to never expire.</p>
 *
 * <p>Reads and writes are logged at {@code WARN}: an outage produces a lot of them, and burying
 * the eviction failures under that noise would defeat the point of separating them.</p>
 */
public class ResilientCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ResilientCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache read failed for {}[{}] - falling through to the source: {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache write failed for {}[{}] - the result is still correct, just not cached: {}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.error("CACHE EVICTION FAILED for {}[{}] - stale data will be served until the TTL "
                        + "expires. This is worth alerting on.",
                cache.getName(), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.error("CACHE CLEAR FAILED for {} - stale data will be served until entries expire.",
                cache.getName(), exception);
    }
}

package com.stockflow.common.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The single cache manager: Redis, with a per-cache TTL taken from {@link CacheNames}.
 *
 * <h2>Redis only — no local in-memory tier</h2>
 *
 * <p>A two-tier cache (Caffeine in front of Redis) is measurably faster for hot keys, and it was
 * rejected. The reason is invalidation: with a local tier, evicting a key has to be broadcast to
 * every instance over pub/sub, and a single missed broadcast leaves one instance serving stale data
 * indefinitely. The symptom is "one of the servers shows the old price", which is close to
 * impossible to reproduce and lands on whoever is on call. One shared cache has one copy of the
 * truth, so an evict is an evict.</p>
 *
 * <h2>What the configuration below prevents</h2>
 *
 * <ul>
 *   <li><b>Version-prefixed keys.</b> Cached values are serialised objects. Deploy a version where
 *       a DTO gained a field, and the old entries deserialise into the new class — at best a null
 *       field, at worst an exception on every read until the TTL expires. Prefixing every key with
 *       the application's cache version makes a deployment start from a clean namespace.</li>
 *   <li><b>Nulls are not cached.</b> Caching a null turns "not found" into a sticky answer: create
 *       the missing product and it stays invisible for the rest of the TTL. The cost is that a
 *       repeated lookup for something that does not exist keeps reaching the database, which is the
 *       cheaper problem — and the rate limiter is the right answer to it, not the cache.</li>
 *   <li><b>Unknown cache names cannot appear.</b> The manager is built from the fixed map in
 *       {@link CacheNames}, so a typo'd {@code @Cacheable("prodcuts")} has no configuration behind
 *       it rather than silently creating a cache nobody sized or monitors.</li>
 *   <li><b>Every mutation waits for the commit.</b> {@code transactionAware()} wraps each cache in
 *       Spring's {@code TransactionAwareCacheDecorator}, which defers {@code put}, {@code evict}
 *       and {@code clear} alike until the surrounding transaction commits. Without it, a
 *       {@code @Cacheable} method inside a transaction that later rolls back has already published
 *       its result, and a {@code @CacheEvict} publishes a hole that a concurrent reader refills
 *       from the pre-commit row — leaving the cache holding a value that never existed in the
 *       database.</li>
 *   <li><b>Values carry their type.</b> See {@link #cacheObjectMapper} for why the cache cannot
 *       share the API's {@code ObjectMapper} unmodified.</li>
 * </ul>
 *
 * <p>{@link TransactionalCacheEvictor} gives the same after-commit guarantee to evictions the
 * annotation cannot express — keys computed inside the method, or several at once.</p>
 */
/*
 * NOTE: bean-method proxying is left ON here, unlike most configuration classes in this codebase.
 * Spring's AbstractCachingConfiguration keeps `configurer::cacheManager` as a Supplier and invokes
 * it on this instance. With proxyBeanMethods = false that invocation builds a SECOND
 * RedisCacheManager rather than returning the singleton, so the @Cacheable interceptor and anything
 * that injects CacheManager - TransactionalCacheEvictor, for one - would be operating on different
 * manager instances with separate internal state. Same reasoning applies to any CachingConfigurer.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Fallback for any cache not named in {@link CacheNames} - short, because it is unreviewed. */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final String cacheVersion;

    /**
     * @param connectionFactory supplied by Spring Boot's Redis auto-configuration
     * @param objectMapper      the application's configured mapper, so cached values serialise the
     *                          same way API responses do — a separate mapper here would quietly
     *                          write dates in a different format inside the cache
     * @param cacheVersion      bump it in {@code application.yml} when a cached type changes shape
     */
    public CacheConfig(RedisConnectionFactory connectionFactory,
                       ObjectMapper objectMapper,
                       @Value("${stockflow.cache.version:v1}") String cacheVersion) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.cacheVersion = cacheVersion;
    }

    @Bean
    @Override
    public CacheManager cacheManager() {
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        CacheNames.ttls().forEach((name, ttl) -> perCache.put(name, configurationWith(ttl)));

        log.info("Cache manager: {} caches, key prefix 'stockflow:{}:', TTLs {}",
                perCache.size(), cacheVersion, CacheNames.ttls());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configurationWith(DEFAULT_TTL))
                .withInitialCacheConfigurations(perCache)
                .transactionAware()
                .build();
    }

    /**
     * One serializer instance shared by every cache configuration. Built lazily and kept, because
     * {@code configurationWith} is called once per declared cache and each call would otherwise
     * copy the {@code ObjectMapper} again.
     */
    private GenericJackson2JsonRedisSerializer valueSerializer;

    private RedisCacheConfiguration configurationWith(Duration ttl) {
        if (valueSerializer == null) {
            valueSerializer = new GenericJackson2JsonRedisSerializer(cacheObjectMapper());
        }
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> "stockflow:" + cacheVersion + ":" + cacheName + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));
    }

    /**
     * The mapper used for cache <i>values</i> — a copy of the application's, with type information
     * switched on.
     *
     * <h2>Why a copy and not the injected mapper</h2>
     *
     * <p>{@code new GenericJackson2JsonRedisSerializer(mapper)} does <b>not</b> enable default
     * typing; only the no-argument constructor does. Handing it a plain mapper therefore writes
     * cache entries with no {@code @class} hint, and reading one back deserialises against
     * {@code Object.class} — which produces a {@code LinkedHashMap}.</p>
     *
     * <p>The failure this causes is a nasty one to diagnose. A {@code @Cacheable ProductDto
     * findBySku(...)} works on the first call, because that call returns the real object from the
     * method. The <i>second</i> call hits the cache, and the cast the caching proxy inserts throws
     * {@code ClassCastException: LinkedHashMap cannot be cast to ProductDto}. It never reproduces
     * in a test that makes one request, and {@link ResilientCacheErrorHandler} does not catch it:
     * the cache lookup succeeded, the cast after it did not.</p>
     *
     * <h2>Why the copy, specifically</h2>
     *
     * <p>{@code activateDefaultTyping} mutates the mapper. Calling it on the injected singleton
     * would add {@code @class} properties to every API response as well — a wire-format change, and
     * a disclosure of internal class names to clients. {@code copy()} keeps the change inside the
     * cache while preserving the date handling and naming strategy the API uses, so a value does
     * not change shape when it passes through Redis.</p>
     *
     * <h2>Why the type validator is not permissive</h2>
     *
     * <p>Default typing means the deserialiser instantiates whatever class the JSON names. That is
     * the classic deserialisation-gadget hole: anyone who can write to Redis can then name a class
     * whose construction has side effects. The allow-list confines it to this application's own
     * types plus the JDK value types they are built from, so a poisoned entry cannot reach a class
     * that does anything on the way in.</p>
     */
    private ObjectMapper cacheObjectMapper() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.stockflow.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.lang.")
                .build();
        return objectMapper.copy().activateDefaultTyping(
                typeValidator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    }

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new ResilientCacheErrorHandler();
    }

    @Bean
    @Override
    public KeyGenerator keyGenerator() {
        return new StableKeyGenerator();
    }
}

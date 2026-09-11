package com.stockflow.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the cache <b>policy</b>, which is the part that causes incidents. The Redis wiring is
 * exercised by the integration tests; what is checked here is that nobody has quietly added a
 * cache that must not exist, or a TTL long enough to outlive a failed eviction.
 */
class CacheConventionsTest {

    @Test
    @DisplayName("every cache has a positive TTL of at most a day")
    void ttlsAreBounded() {
        Map<String, Duration> ttls = CacheNames.ttls();
        assertThat(ttls).isNotEmpty();
        ttls.forEach((name, ttl) -> {
            assertThat(ttl).as("%s TTL", name).isPositive();
            // A failed eviction is swallowed (see ResilientCacheErrorHandler); the TTL is what
            // bounds how long the resulting stale data can be served. A week-long TTL means a
            // week of wrong prices.
            assertThat(ttl).as("%s TTL is too long to bound a failed eviction", name)
                    .isLessThanOrEqualTo(Duration.ofDays(1));
        });
    }

    @Test
    @DisplayName("stock and availability are deliberately not cacheable")
    void noStockCache() {
        // Caching available-to-promise is how a system oversells: the cached figure says 5, three
        // customers each check out 3, and the warehouse finds out at picking. See CacheNames.
        assertThat(CacheNames.ttls().keySet())
                .noneMatch(name -> name.toLowerCase().contains("stock")
                        || name.toLowerCase().contains("atp")
                        || name.toLowerCase().contains("availab"));
    }

    @Test
    @DisplayName("permissions expire quickly enough that revoking access takes effect")
    void permissionTtlIsShort() {
        assertThat(CacheNames.ttls().get(CacheNames.USER_PERMISSIONS))
                .isLessThanOrEqualTo(Duration.ofMinutes(15));
        assertThat(CacheNames.ttls().get(CacheNames.PRICES))
                .isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("the TTL map cannot be mutated by a caller")
    void ttlsAreImmutable() {
        assertThatThrownBy(() -> CacheNames.ttls().put("sneaky", Duration.ofDays(30)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- key generator

    @SuppressWarnings("unused")
    static class ExampleService {
        public Object findById(UUID id) { return null; }
        public Object findArchivedById(UUID id) { return null; }
    }

    @Test
    @DisplayName("two methods taking the same argument do not share a cache key")
    void methodNameIsPartOfTheKey() throws Exception {
        StableKeyGenerator generator = new StableKeyGenerator();
        ExampleService target = new ExampleService();
        Method findById = ExampleService.class.getMethod("findById", UUID.class);
        Method findArchived = ExampleService.class.getMethod("findArchivedById", UUID.class);
        UUID id = UUID.randomUUID();

        // Spring's SimpleKeyGenerator omits the method name, so these two would collide and one
        // method would return the other's cached result.
        assertThat(generator.generate(target, findById, id))
                .isNotEqualTo(generator.generate(target, findArchived, id));
    }

    @Test
    @DisplayName("the key is built from toString, so it is stable across JVMs")
    void keyIsValueBasedNotIdentityBased() throws Exception {
        StableKeyGenerator generator = new StableKeyGenerator();
        Method findById = ExampleService.class.getMethod("findById", UUID.class);
        UUID id = UUID.randomUUID();

        // With an identity-based hash, two instances of the application would write the same
        // logical entry under different keys - halving the hit rate and breaking eviction.
        assertThat(generator.generate(new ExampleService(), findById, id))
                .isEqualTo(generator.generate(new ExampleService(), findById, id));
    }

    @Test
    @DisplayName("a very long argument is bounded without two of them colliding")
    void longArgumentsAreHashedNotTruncated() throws Exception {
        StableKeyGenerator generator = new StableKeyGenerator();
        Method findById = ExampleService.class.getMethod("findById", UUID.class);

        String sharedPrefix = "X".repeat(500);
        Object first = generator.generate(new ExampleService(), findById, sharedPrefix + "A");
        Object second = generator.generate(new ExampleService(), findById, sharedPrefix + "B");

        assertThat(first.toString()).hasSizeLessThan(200);
        // Plain truncation would make these identical, so two different lookups would share a key.
        assertThat(first).isNotEqualTo(second);
    }
}

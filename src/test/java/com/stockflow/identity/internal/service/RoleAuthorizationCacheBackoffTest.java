package com.stockflow.identity.internal.service;

import com.stockflow.common.security.PermissionCode;
import com.stockflow.identity.internal.repository.RoleGrantRow;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis being down must cost one timeout per back-off window, not one per request — measured
 * against a stopped Redis, every request was 2 s slower before the back-off existed.
 */
class RoleAuthorizationCacheBackoffTest {

    private static final Instant T0 = Instant.parse("2026-10-01T06:00:00Z");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private final RoleJpaRepository roles = mock(RoleJpaRepository.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(T0);
    private RoleAuthorizationCache cache;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(roles.findGrantsByCode("PROCUREMENT_STAFF")).thenReturn(List.of(row(3, "procurement-purchase-orders", "READ")));
        cache = new RoleAuthorizationCache(redis, roles, tx, clock, "v1");
    }

    @Test
    void aRedisFailureIsAnsweredFromTheDatabaseAndRedisIsLeftAloneForTheBackoff() {
        when(hash.multiGet(any(), any())).thenThrow(new RedisConnectionFailureException("refused"));

        assertThat(cache.resolve(List.of("PROCUREMENT_STAFF")).permissions())
                .containsExactly(PermissionCode.parse("procurement-purchase-orders:READ"));
        assertThat(cache.resolve(List.of("PROCUREMENT_STAFF")).permissions()).hasSize(1);
        clock.advance(RoleAuthorizationCache.REDIS_BACKOFF.minusSeconds(1));
        cache.resolve(List.of("PROCUREMENT_STAFF"));

        verify(hash, times(1)).multiGet(any(), any());

        clock.advance(Duration.ofSeconds(2));
        cache.resolve(List.of("PROCUREMENT_STAFF"));
        verify(hash, times(2)).multiGet(any(), any());   // tried again once the window passed
    }

    @Test
    void aDatabaseFailureIsNotMistakenForARedisFailure() {
        when(hash.multiGet(any(), any())).thenThrow(new RedisConnectionFailureException("refused"));
        when(roles.findGrantsByCode("PROCUREMENT_STAFF"))
                .thenThrow(new DataAccessResourceFailureException("postgres down"));

        // Redis down AND Postgres down: unanswerable, so it must propagate (-> 503), never grant.
        assertThatThrownBy(() -> cache.resolve(List.of("PROCUREMENT_STAFF")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void aHealthyRedisNeverOpensTheBackoff() {
        when(hash.multiGet(any(), any())).thenReturn(java.util.Arrays.asList((Object) null));
        when(roles.findAllVersions()).thenThrow(new DataAccessResourceFailureException("postgres down"));

        assertThatThrownBy(() -> cache.resolve(List.of("PROCUREMENT_STAFF")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> cache.resolve(List.of("PROCUREMENT_STAFF")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(hash, times(2)).multiGet(any(), any());
        verify(roles, never()).findGrantsByCode(any());
    }

    private static RoleGrantRow row(long version, String resource, String action) {
        return new RoleGrantRow() {
            public Long getVersion() { return version; }
            public String getResource() { return resource; }
            public String getAction() { return action; }
            public String getDataScope() { return "ALL"; }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

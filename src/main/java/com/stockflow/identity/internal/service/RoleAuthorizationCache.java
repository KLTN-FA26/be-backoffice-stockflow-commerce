package com.stockflow.identity.internal.service;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.ResolvedAuthorization;
import com.stockflow.common.security.RoleAuthorizationLookup;
import com.stockflow.identity.internal.repository.RoleGrantRow;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.RoleVersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Answers "what do these roles grant" from Redis, keyed by role and role version (ADR-0008).
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * stockflow:{cacheVersion}:authz:role-versions        HASH  roleCode -> version        TTL 60 s
 * stockflow:{cacheVersion}:authz:role:{code}:v{ver}   SET   resource:ACTION, plus "~"  TTL 1 h
 * </pre>
 *
 * <h2>Why the version is in the key, and why nothing is ever deleted</h2>
 *
 * <p>The obvious design — one key per role, deleted when an administrator edits the role — has a
 * race that leaves stale grants in place: a request reads the old grants from the database, the
 * edit commits and deletes the key, and the request then writes the old grants back. With the
 * version in the key that late write lands under the old version, which no reader asks for once the
 * pointer has moved. An edit therefore only advances the pointer; old keys expire on their own.</p>
 *
 * <p>The version is the role's own {@code app_role.version}, advanced by
 * {@code RoleJpaRepository#advanceVersion} in the same transaction as the grant change. A
 * timestamp would collide for two edits in one millisecond, and a counter kept only in Redis would
 * restart from 1 after a flush and match a stale key that survived it.</p>
 *
 * <h2>Two rules that keep a key honest</h2>
 *
 * <ul>
 *   <li><b>Grants are cached under the version read with them</b>, in the same SQL statement
 *       ({@code findGrantsByCode}), never under the version the pointer said. A pointer that lags
 *       the database therefore costs a miss, not a wrong answer.</li>
 *   <li><b>The pointer only moves forward.</b> {@link #ADVANCE_VERSIONS} is a compare-and-set in
 *       Lua; an after-commit callback that runs late cannot put back an older version.</li>
 * </ul>
 *
 * <p>If the pointer cannot be advanced after a commit (Redis briefly down), readers keep using the
 * previous version until the pointer expires. {@link #VERSION_TTL} is what bounds that window, and
 * is why it is a minute rather than an hour: reloading ten rows once a minute is free.</p>
 *
 * <h2>When Redis is down</h2>
 *
 * <p>Lookups fall back to Postgres. After a Redis failure this instance stops asking Redis for
 * {@link #REDIS_BACKOFF}: without that, every request waited out the 2-second Redis timeout before
 * falling back — measured, a whole API two seconds slower for as long as Redis was away. Only a Redis
 * failure opens the back-off; a database failure propagates and the request is refused with a 503 —
 * see {@code StockflowJwtAuthenticationConverter}. Nothing here ever turns a failure into a grant.</p>
 */
@Component
class RoleAuthorizationCache implements RoleAuthorizationLookup {

    private static final Logger log = LoggerFactory.getLogger(RoleAuthorizationCache.class);

    static final Duration VERSION_TTL = Duration.ofSeconds(60);
    static final Duration GRANTS_TTL = Duration.ofHours(1);

    /** How long to read straight from Postgres after Redis failed, before trying Redis again. */
    static final Duration REDIS_BACKOFF = Duration.ofSeconds(30);

    /** Always stored in a role's set, so a role that grants nothing is a cached answer rather than
     *  a miss — Redis cannot hold an empty set. Never a valid permission code. */
    static final String PRESENT = "~";

    /**
     * {@code HSET field value} for each pair, but only where the stored value is absent or lower.
     * ARGV: code1, version1, code2, version2, ..., ttlMillis.
     */
    private static final RedisScript<Long> ADVANCE_VERSIONS = RedisScript.of("""
            local ttl = tonumber(ARGV[#ARGV])
            for i = 1, #ARGV - 1, 2 do
              local current = redis.call('HGET', KEYS[1], ARGV[i])
              if (not current) or tonumber(current) < tonumber(ARGV[i + 1]) then
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
              end
            end
            redis.call('PEXPIRE', KEYS[1], ttl)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RoleJpaRepository roles;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final String prefix;
    private volatile Instant redisBackoffUntil = Instant.MIN;

    RoleAuthorizationCache(StringRedisTemplate redis, RoleJpaRepository roles,
                           PlatformTransactionManager transactionManager, Clock clock,
                           @Value("${stockflow.cache.version:v1}") String cacheVersion) {
        this.redis = redis;
        this.roles = roles;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.prefix = "stockflow:" + cacheVersion + ":authz:";
    }

    @Override
    public ResolvedAuthorization resolve(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return ResolvedAuthorization.none();
        }
        List<String> codes = List.copyOf(new LinkedHashSet<>(roleCodes));
        Map<String, Set<PermissionCode>> grants;
        if (clock.instant().isBefore(redisBackoffUntil)) {
            grants = fromDatabase(codes);
        } else {
            try {
                grants = fromCache(codes);
            } catch (CacheUnavailableException cacheDown) {
                redisBackoffUntil = clock.instant().plus(REDIS_BACKOFF);
                log.warn("Authorisation cache unavailable ({}); reading grants from the database for "
                        + "the next {}", cacheDown.getCause().toString(), REDIS_BACKOFF);
                grants = fromDatabase(codes);
            }
        }
        if (grants.isEmpty()) {
            return ResolvedAuthorization.none();
        }
        Set<PermissionCode> union = new LinkedHashSet<>();
        grants.values().forEach(union::addAll);
        return new ResolvedAuthorization(union, DataScope.ALL);
    }

    /**
     * Moves the version pointer after a committed grant change. Never throws: the change is already
     * committed, and a failure here only delays it by at most {@link #VERSION_TTL}.
     */
    void publishVersion(String roleCode, long version) {
        try {
            advance(Map.of(roleCode, version));
        } catch (RuntimeException ex) {
            log.error("Could not advance the permission version of role {} to {}; holders keep the "
                    + "previous grants for up to {}", roleCode, version, VERSION_TTL, ex);
        }
    }

    // ---- cache path ------------------------------------------------------------------------

    private Map<String, Set<PermissionCode>> fromCache(List<String> codes) {
        Map<String, Long> versions = currentVersions(codes);
        List<String> known = codes.stream().filter(versions::containsKey).toList();
        if (known.isEmpty()) {
            return Map.of();
        }

        List<Object> cached = onRedis(() -> redis.executePipelined((RedisCallback<Object>) connection -> {
            for (String code : known) {
                connection.setCommands().sMembers(bytes(grantsKey(code, versions.get(code))));
            }
            return null;
        }));

        Map<String, Set<PermissionCode>> grants = new LinkedHashMap<>();
        for (int i = 0; i < known.size(); i++) {
            String code = known.get(i);
            Set<?> members = cached.get(i) instanceof Set<?> set ? set : Set.of();
            if (members.isEmpty()) {
                long pointer = versions.get(code);
                loadRole(code).ifPresent(loaded -> {
                    store(code, loaded);
                    if (loaded.version() > pointer) {
                        // The pointer lagged the database; move it so the next request hits.
                        advance(Map.of(code, loaded.version()));
                    }
                    grants.put(code, loaded.permissions());
                });
            } else {
                grants.put(code, parse(code, members));
            }
        }
        return grants;
    }

    private Map<String, Long> currentVersions(List<String> codes) {
        List<Object> stored = onRedis(() -> redis.opsForHash().multiGet(versionsKey(), new ArrayList<>(codes)));
        Map<String, Long> versions = new HashMap<>();
        boolean incomplete = false;
        for (int i = 0; i < codes.size(); i++) {
            Object value = stored.get(i);
            if (value == null) {
                incomplete = true;
            } else {
                versions.put(codes.get(i), Long.parseLong(value.toString()));
            }
        }
        if (incomplete) {
            Map<String, Long> fromDatabase = loadVersions();
            advance(fromDatabase);
            codes.forEach(code -> {
                if (!versions.containsKey(code) && fromDatabase.containsKey(code)) {
                    versions.put(code, fromDatabase.get(code));
                }
            });
        }
        return versions;
    }

    private void store(String code, LoadedRole loaded) {
        String key = grantsKey(code, loaded.version());
        List<String> members = new ArrayList<>();
        members.add(PRESENT);
        loaded.permissions().forEach(p -> members.add(p.toString()));
        onRedis(() -> {
            redis.opsForSet().add(key, members.toArray(String[]::new));
            return redis.expire(key, GRANTS_TTL);
        });
    }

    private void advance(Map<String, Long> versions) {
        if (versions.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>();
        versions.forEach((code, version) -> {
            args.add(code);
            args.add(String.valueOf(version));
        });
        args.add(String.valueOf(VERSION_TTL.toMillis()));
        onRedis(() -> redis.execute(ADVANCE_VERSIONS, List.of(versionsKey()), args.toArray()));
    }

    /** Runs a Redis operation, marking any failure as a cache failure so that it — and only it —
     *  opens the back-off. A database failure must stay a database failure. */
    private static <T> T onRedis(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException ex) {
            throw new CacheUnavailableException(ex);
        }
    }

    /** A malformed member can only come from someone writing to Redis by hand; it is dropped, and
     *  dropping a permission is the safe direction to fail. */
    private static Set<PermissionCode> parse(String roleCode, Set<?> members) {
        Set<PermissionCode> permissions = new LinkedHashSet<>();
        for (Object member : members) {
            String raw = member.toString();
            if (PRESENT.equals(raw)) {
                continue;
            }
            try {
                permissions.add(PermissionCode.parse(raw));
            } catch (IllegalArgumentException malformed) {
                log.warn("Ignoring malformed cached permission '{}' of role {}", raw, roleCode);
            }
        }
        return permissions;
    }

    // ---- database path ---------------------------------------------------------------------

    private Map<String, Set<PermissionCode>> fromDatabase(List<String> codes) {
        Map<String, Set<PermissionCode>> grants = new LinkedHashMap<>();
        for (String code : codes) {
            loadRole(code).ifPresent(loaded -> grants.put(code, loaded.permissions()));
        }
        return grants;
    }

    private Map<String, Long> loadVersions() {
        List<RoleVersionRow> rows = readOnly.execute(status -> roles.findAllVersions());
        Map<String, Long> versions = new HashMap<>();
        if (rows != null) {
            rows.forEach(row -> versions.put(row.getCode(), row.getVersion()));
        }
        return versions;
    }

    private Optional<LoadedRole> loadRole(String code) {
        List<RoleGrantRow> rows = readOnly.execute(status -> roles.findGrantsByCode(code));
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Set<PermissionCode> permissions = new LinkedHashSet<>();
        for (RoleGrantRow row : rows) {
            if (row.getResource() == null || row.getAction() == null) {
                continue;
            }
            try {
                permissions.add(PermissionCode.of(row.getResource(), Action.valueOf(row.getAction())));
            } catch (IllegalArgumentException unknownAction) {
                log.warn("Ignoring permission {}:{} of role {}: not a valid permission code",
                        row.getResource(), row.getAction(), code);
            }
        }
        return Optional.of(new LoadedRole(rows.get(0).getVersion(), permissions));
    }

    // ---- keys ------------------------------------------------------------------------------

    private String versionsKey() {
        return prefix + "role-versions";
    }

    private String grantsKey(String code, long version) {
        return prefix + "role:" + code + ":v" + version;
    }

    private static byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private record LoadedRole(long version, Set<PermissionCode> permissions) {
    }

    private static final class CacheUnavailableException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        CacheUnavailableException(RuntimeException cause) {
            super(cause);
        }
    }
}

package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Runs work as one user, outside any request: the only place that builds an authorization no request carried (ADR-0012).
 *
 * <p><b>What the user holds now, nothing more.</b> A background job that acts for someone (the assistant's watch, which
 * reads that person's own lists to tell them what is new) must see exactly what that person sees on their screens. A
 * "system" principal would see every warehouse: {@code WarehouseScope} does not limit a caller with no scope, and a
 * manager of one warehouse would be told about all of them. So the authorization is the one a request of theirs would
 * get, built the way {@link StockflowJwtAuthenticationConverter} builds it: the user's roles as stored now
 * ({@link UserAccountLookup}), their grants and scope ({@link RoleAuthorizationLookup}), their warehouses when the scope
 * is a warehouse's ({@link UserWarehouseLookup}). A role revoked, an account disabled or locked applies on the next run;
 * nothing is cached here.</p>
 *
 * <p><b>Refused</b>, with nothing run: an unknown user, an account that may not sign in, one with no role, and a portal
 * account ({@link CurrentUser#isPortalCustomer}, read from the authorization as built). A lookup that fails refuses
 * too: not knowing someone's grants is not having them.</p>
 *
 * <p><b>Marked.</b> The token's {@code act} claim names who is acting ({@code {"sub": actor}}, RFC 8693's actor claim),
 * and the trail reads it ({@link CurrentUserProvider#auditName}): rows written meanwhile are created by
 * "{@code username via assistant-watch}", audit entries likewise, so the person is told from the job acting for them.
 * The token is never sent anywhere: it has no signature, and lives only in this thread's security context while the
 * work runs.</p>
 *
 * <p><b>On the calling thread, then restored.</b> The security context is a thread-local; the caller's own one (none,
 * on a scheduler thread) is put back afterwards, and the data scope is restored as found, so nothing of this user
 * outlives the call. Work handed to another thread inherits nothing unless it is carried ({@code ThreadContext}).</p>
 *
 * <p><b>Also for a personal token</b> ({@link PersonalTokens}, ADR-0013): a request that carries one instead of a JWT
 * is authenticated with {@link #authenticationFor}, the same authorization built the same way, which the request then
 * carries as any other. The token says only whose it is.</p>
 */
@Component
public class RunAs {

    private static final Logger log = LoggerFactory.getLogger(RunAs.class);


    /**
     * How long the token says it is valid. Nothing checks it: the token never leaves this thread's security context,
     * which is cleared when the work ends. It is set past any one job's work for one user (the watch's run budget) only
     * so that the claims read as a token's would.
     */
    static final Duration VALID_FOR = Duration.ofHours(1);

    /** Same rule as {@code ck_app_role_code_format}. */
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private static final Pattern ACTOR = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    private final java.util.function.Supplier<UserAccountLookup> accounts;
    private final java.util.function.Supplier<RoleAuthorizationLookup> authorizations;
    private final java.util.function.Supplier<UserWarehouseLookup> warehouses;
    private final CurrentUserProvider currentUsers;
    private final Clock clock;

    /**
     * The lookups are the identity module's, asked for when used: {@code common} is shared by every module, and a test
     * of a module that bootstraps no identity must still start. Without them nobody can be acted for (UNAVAILABLE).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RunAs(org.springframework.beans.factory.ObjectProvider<UserAccountLookup> accounts,
                 org.springframework.beans.factory.ObjectProvider<RoleAuthorizationLookup> authorizations,
                 org.springframework.beans.factory.ObjectProvider<UserWarehouseLookup> warehouses,
                 CurrentUserProvider currentUsers, Clock clock) {
        this.accounts = accounts::getIfAvailable;
        this.authorizations = authorizations::getIfAvailable;
        this.warehouses = warehouses::getIfAvailable;
        this.currentUsers = currentUsers;
        this.clock = clock;
    }

    /** With the lookups given directly, as a test builds it. */
    public RunAs(UserAccountLookup accounts, RoleAuthorizationLookup authorizations, UserWarehouseLookup warehouses,
                 CurrentUserProvider currentUsers, Clock clock) {
        this.accounts = () -> accounts;
        this.authorizations = () -> authorizations;
        this.warehouses = () -> warehouses;
        this.currentUsers = currentUsers;
        this.clock = clock;
    }

    /** Why a user could not be acted for; nothing was run. */
    public enum Refusal { NO_SUCH_USER, INACTIVE, NO_STAFF_ROLE, UNAVAILABLE }

    /** The user could not be acted for, and {@link #refusal()} says why. */
    public static final class RefusedException extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final transient Refusal refusal;

        RefusedException(Refusal refusal, UUID userId, Throwable cause) {
            super("Cannot act for user " + userId + ": " + refusal, cause);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * Runs {@code work} as {@code userId}, with what they hold now.
     *
     * @param actor who acts for them, lower case with dashes ({@code assistant-watch}); named in the token's
     *              {@code act} claim
     * @param work  given the user as a request of theirs would see them; runs on this thread
     * @throws RefusedException when the user cannot be acted for (class javadoc); {@code work} has not run
     */
    public <T> T call(UUID userId, String actor, Function<CurrentUser, T> work) {
        StockflowAuthenticationToken token = authenticationFor(userId, actor);
        SecurityContext previous = SecurityContextHolder.getContext();
        java.util.Optional<DataScopeContext.Scope> scope = DataScopeContext.current();
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(token);
        SecurityContextHolder.setContext(acting);
        DataScopeContext.clear();
        try {
            CurrentUser user = currentUsers.current().orElseThrow(() -> new IllegalStateException(
                    "the token built for " + userId + " does not read as a user"));
            return work.apply(user);
        } finally {
            SecurityContextHolder.setContext(previous);
            DataScopeContext.restore(scope.orElse(null));
        }
    }

    /**
     * The authorization {@code userId} holds now, marked as {@code actor}'s, without running anything or touching the
     * security context: what a request carrying a personal token is authenticated with
     * ({@link PersonalTokenAuthenticationProvider}).
     *
     * @throws RefusedException when the user cannot be acted for (class javadoc)
     */
    public StockflowAuthenticationToken authenticationFor(UUID userId, String actor) {
        if (userId == null || actor == null || !ACTOR.matcher(actor).matches()) {
            throw new IllegalArgumentException("a user and an actor such as assistant-watch are required");
        }
        StockflowAuthenticationToken token = tokenFor(userId, actor);
        CurrentUser user = CurrentUserProvider.userOf(token).orElseThrow(() -> new IllegalStateException(
                "the token built for " + userId + " does not read as a user"));
        if (user.isPortalCustomer()) {
            throw new RefusedException(Refusal.NO_STAFF_ROLE, userId, null);
        }
        return token;
    }

    private StockflowAuthenticationToken tokenFor(UUID userId, String actor) {
        UserAccountLookup.UserAccount account;
        ResolvedAuthorization resolved;
        AssignedWarehouses assigned = AssignedWarehouses.none();
        Set<String> roles = new LinkedHashSet<>();
        try {
            UserAccountLookup accountLookup = required(accounts);
            account = accountLookup.account(userId).orElseThrow(() -> new RefusedException(Refusal.NO_SUCH_USER,
                    userId, null));
            if (!account.active()) {
                throw new RefusedException(Refusal.INACTIVE, userId, null);
            }
            for (String role : account.roles()) {
                if (role != null && ROLE_CODE.matcher(role).matches()) {
                    roles.add(role);
                }
            }
            if (roles.isEmpty()) {
                throw new RefusedException(Refusal.NO_STAFF_ROLE, userId, null);
            }
            resolved = required(authorizations).resolve(roles);
            if (resolved.scope() == DataScope.WAREHOUSE) {
                assigned = required(warehouses).warehousesOf(userId);
            }
        } catch (RefusedException refused) {
            throw refused;
        } catch (RuntimeException unavailable) {
            log.error("Cannot resolve the authorization of user {} to act for them; refusing", userId, unavailable);
            throw new RefusedException(Refusal.UNAVAILABLE, userId, unavailable);
        }

        Instant now = clock.instant();
        Jwt jwt = Jwt.withTokenValue("run-as")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("preferred_username", account.username())
                .claim(StockflowJwtAuthenticationConverter.ROLES_CLAIM, java.util.List.copyOf(roles))
                .claim(CurrentUserProvider.ACTOR_CLAIM, Map.of("sub", actor))
                .issuedAt(now)
                .expiresAt(now.plus(VALID_FOR))
                .build();
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(role)));
        resolved.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code.toString())));
        return new StockflowAuthenticationToken(jwt, authorities, resolved.scope(), assigned);
    }

    private static <T> T required(java.util.function.Supplier<T> provider) {
        T value = provider.get();
        if (value == null) {
            throw new IllegalStateException("the identity module's lookups are not in this application context");
        }
        return value;
    }
}

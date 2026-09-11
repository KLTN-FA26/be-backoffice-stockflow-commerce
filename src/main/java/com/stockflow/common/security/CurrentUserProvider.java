package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads the current {@link CurrentUser} out of the security context.
 *
 * <h2>This method must never throw</h2>
 *
 * <p>Its signature promises an {@code Optional}, and its callers believe it. Two of them —
 * {@code RequestLoggingFilter} and the audit aspect — call it from a {@code finally} block. An
 * exception thrown there <b>replaces</b> whatever the request was about to return, escapes
 * {@code OncePerRequestFilter} entirely (so {@code @RestControllerAdvice} never sees it) and
 * reaches the container: a Tomcat HTML error page instead of the JSON envelope, or a truncated
 * response if the body was already committed. And it would happen on every single request from the
 * principal concerned, not occasionally.</p>
 *
 * <p>So every claim read here is defensive. The JWT decoder validates a token's signature, issuer
 * and expiry; it does not validate that {@code sub} is a UUID or that {@code scope_level} spells
 * one of our enum constants. A numeric subject, an e-mail subject, an absent subject, or an issuer
 * that adds a new scope level next quarter are all ordinary token shapes, and none of them is a
 * reason to break the response.</p>
 *
 * <p>Where a claim cannot be understood the answer is the <b>narrow</b> one — the narrowest scope,
 * or no user at all — never a wide default.</p>
 */
@Component
public class CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserProvider.class);

    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return Optional.empty();     // anonymous, a scheduled job, or an async event listener
        }
        Jwt jwt = token.getToken();

        Set<Role> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(Role::fromAuthority)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        Set<PermissionCode> permissions = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.indexOf(PermissionCode.SEPARATOR) > 0)
                .map(PermissionCode::parse)
                .collect(Collectors.toSet());

        UUID userId = parseSubject(jwt.getSubject());
        if (userId == null) {
            // No usable identity. Empty rather than a partly-built user: every caller already
            // handles empty, and a CurrentUser with a null userId is the shape that makes
            // DataScopeSpecifications' OWN branch dangerous.
            return Optional.empty();
        }

        List<String> warehouses = jwt.getClaimAsStringList("warehouses");

        return Optional.of(new CurrentUser(
                userId,
                jwt.getClaimAsString("preferred_username"),
                roles,
                permissions,
                parseScope(jwt.getClaimAsString("scope_level")),
                warehouses == null ? Set.of() : Set.copyOf(warehouses)));
    }

    /**
     * @return the narrowest scope for anything not recognised — {@code orElse} alone only covers a
     *         claim that is absent, not one that is present and unexpected
     */
    private static DataScope parseScope(String claim) {
        if (claim == null || claim.isBlank()) {
            return DataScope.OWN;
        }
        try {
            return DataScope.valueOf(claim.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownLevel) {
            log.warn("Token carries an unrecognised scope_level '{}'; falling back to {}. Either "
                            + "the issuer added a level this application does not know, or the "
                            + "claim is being populated from the wrong field.",
                    claim, DataScope.OWN);
            return DataScope.OWN;
        }
    }

    /** @return null when the subject is absent or is not a UUID, both of which are token shapes we
     *          simply cannot map onto a user of this system */
    private static UUID parseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            log.warn("Token has no 'sub' claim; treating the request as unauthenticated");
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            log.warn("Token subject '{}' is not a UUID; treating the request as unauthenticated. "
                    + "User ids in this system are UUIDs - check the issuer's subject mapping.",
                    subject);
            return null;
        }
    }
}

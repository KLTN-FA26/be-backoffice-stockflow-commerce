package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the {@code roles} and {@code permissions} claims of the JWT into Spring Security
 * authorities.
 *
 * <p><b>Why this class is not optional.</b> Spring Security's default converter reads the
 * {@code scope} / {@code scp} claim and prefixes every value with {@code SCOPE_}. Anything in a
 * custom claim is ignored, so a permission check would never match and every guarded endpoint
 * would answer 403 with nothing in the log to explain it.</p>
 *
 * <p><b>On putting permissions in the token.</b> A role with 134 of 382 permissions produces
 * roughly 3 KB of claim, which is close to the practical header limit and grows with the product.
 * More importantly a token is a snapshot: revoking a permission does not reach a token already
 * issued. Both problems have the same fix and it is deliberately not implemented here - the token
 * should carry only {@code roles} plus a {@code perm_ver} counter, with each service resolving
 * role to permission from a locally cached catalog and rejecting a token whose {@code perm_ver} is
 * behind. Until identity-service exists, the {@code permissions} claim below keeps the skeleton
 * runnable; see ADR-0004 for the migration path.</p>
 *
 * <p>Unknown roles and malformed permission codes are dropped rather than trusted: identity-service
 * is the only authority on what is valid, and a token carrying something else must not gain access.</p>
 */
public class StockflowJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(StockflowJwtAuthenticationConverter.class);

    public static final String ROLES_CLAIM = "roles";
    public static final String PERMISSIONS_CLAIM = "permissions";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles != null) {
            roles.stream()
                    .map(Role::fromAuthority)
                    .flatMap(Optional::stream)
                    .map(role -> new SimpleGrantedAuthority(role.authority()))
                    .forEach(authorities::add);
        }

        List<String> permissions = jwt.getClaimAsStringList(PERMISSIONS_CLAIM);
        if (permissions != null) {
            for (String raw : permissions) {
                try {
                    authorities.add(new SimpleGrantedAuthority(PermissionCode.parse(raw).toString()));
                } catch (IllegalArgumentException ex) {
                    log.warn("Dropping malformed permission '{}' from token of subject {}",
                            raw, jwt.getSubject());
                }
            }
        }

        return authorities;
    }
}

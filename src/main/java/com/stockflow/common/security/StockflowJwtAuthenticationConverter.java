package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a token into Spring Security authorities: the {@code roles} claim from the token, and the
 * permissions those roles grant from {@link RoleAuthorizationLookup}.
 *
 * <p><b>Why this class is not optional.</b> Spring Security's default converter reads the
 * {@code scope} / {@code scp} claim and prefixes every value with {@code SCOPE_}. Anything in a
 * custom claim is ignored, so a permission check would never match and every guarded endpoint
 * would answer 403 with nothing in the log to explain it.</p>
 *
 * <h2>Permissions are not read from the token (ADR-0008)</h2>
 *
 * <p>Tokens used to carry every permission. A role with 134 of 382 permissions made a 4 KB token,
 * which the frontend sent twice (cookie and header) and Tomcat refused as an oversized header; and
 * a token is a snapshot, so unticking a permission reached nobody until their token expired. The
 * token now names only the roles, and the permissions are resolved here on every request from a
 * cache keyed by role and role version.</p>
 *
 * <p>A token issued before this change still has a {@code permissions} claim. It is ignored on
 * purpose, not merged: trusting it would keep a revoked permission alive for the rest of that
 * token's life, which is the defect this design exists to remove.</p>
 *
 * <p>Unknown roles are dropped rather than trusted: identity is the only authority on what is
 * valid, and a token carrying something else must not gain access.</p>
 *
 * <h2>When the lookup cannot answer</h2>
 *
 * <p>The request is refused with {@link AuthenticationServiceException} — an authorisation question
 * nobody could answer is never a yes. In practice this means Redis <i>and</i> Postgres are both
 * down; the lookup falls back to the database on its own when only Redis is, and a token cannot even
 * reach this point without the session check in the decoder reading Postgres first.</p>
 */
public class StockflowJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(StockflowJwtAuthenticationConverter.class);

    public static final String ROLES_CLAIM = "roles";

    private final RoleAuthorizationLookup lookup;

    public StockflowJwtAuthenticationConverter(RoleAuthorizationLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = knownRoles(jwt);

        ResolvedAuthorization resolved;
        try {
            resolved = roles.isEmpty() ? ResolvedAuthorization.none() : lookup.resolve(roles);
        } catch (RuntimeException unavailable) {
            log.error("Cannot resolve permissions for subject {} with roles {}; refusing the request",
                    jwt.getSubject(), roles, unavailable);
            throw new AuthenticationServiceException("Permissions are unavailable", unavailable);
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(role)));
        resolved.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code.toString())));
        return new StockflowAuthenticationToken(jwt, authorities, resolved.scope());
    }

    private static Set<String> knownRoles(Jwt jwt) {
        Set<String> known = new LinkedHashSet<>();
        List<String> claimed = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (claimed == null) {
            return known;
        }
        for (String raw : claimed) {
            Optional<Role> role = Role.fromAuthority(raw);
            if (role.isPresent()) {
                known.add(role.get().authority());
            } else {
                log.warn("Dropping unknown role '{}' from token of subject {}", raw, jwt.getSubject());
            }
        }
        return known;
    }
}

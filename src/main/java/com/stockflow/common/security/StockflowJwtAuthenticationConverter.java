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
 * <p>Roles are no longer limited to the {@link Role} enum: administrators can add roles at runtime
 * (SCRUM-455). A role code is kept if it has the shape of one; whether it grants anything is
 * decided by the lookup, which only knows roles that exist in {@code identity.app_role} — a code
 * that does not exist there grants nothing. A value that is not even shaped like a role code is
 * dropped, so it can never be mistaken for a permission authority (those contain {@code ':'}).</p>
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

    /** Same rule as {@code ck_app_role_code_format}. */
    private static final java.util.regex.Pattern ROLE_CODE = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

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
            if (raw != null && ROLE_CODE.matcher(raw).matches()) {
                known.add(raw);
            } else {
                log.warn("Dropping malformed role '{}' from token of subject {}", raw, jwt.getSubject());
            }
        }
        return known;
    }
}

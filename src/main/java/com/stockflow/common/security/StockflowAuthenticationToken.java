package com.stockflow.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * A {@link JwtAuthenticationToken} that also carries the data scope resolved on the server.
 *
 * <p>A subclass rather than a {@code details} object: Spring's bearer-token filter writes the
 * request's {@code WebAuthenticationDetails} into {@code details} when the converter leaves it
 * empty, and code that reads the client address from there would stop seeing it. Being a
 * {@code JwtAuthenticationToken} still, everything that expects one — {@code @AuthenticationPrincipal
 * Jwt}, {@link CurrentUserProvider}'s type check — keeps working unchanged.</p>
 */
public class StockflowAuthenticationToken extends JwtAuthenticationToken {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final DataScope scope;

    public StockflowAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities,
                                        DataScope scope) {
        super(jwt, authorities, jwt.getSubject());
        this.scope = scope;
    }

    public DataScope scope() {
        return scope;
    }
}

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
    private final AssignedWarehouses warehouses;

    public StockflowAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities,
                                        DataScope scope) {
        this(jwt, authorities, scope, AssignedWarehouses.none());
    }

    public StockflowAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities,
                                        DataScope scope, AssignedWarehouses warehouses) {
        super(jwt, authorities, jwt.getSubject());
        this.scope = scope;
        this.warehouses = warehouses == null ? AssignedWarehouses.none() : warehouses;
    }

    /** The warehouses resolved on the server for a warehouse-bound caller (SCRUM-457); else none. */
    public AssignedWarehouses warehouses() {
        return warehouses;
    }

    public DataScope scope() {
        return scope;
    }
}

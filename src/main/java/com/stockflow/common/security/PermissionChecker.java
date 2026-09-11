package com.stockflow.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single place that answers "may this caller do this?".
 *
 * <p>Permissions arrive as authorities of the form {@code stock-items:CREATE}, resolved by
 * {@link StockflowJwtAuthenticationConverter}.</p>
 */
@Component
public class PermissionChecker {

    public boolean has(PermissionCode code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return granted(authentication).contains(code.toString());
    }

    public void require(PermissionCode code) {
        if (!has(code)) {
            throw new PermissionDeniedException(code);
        }
    }

    private Set<String> granted(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}

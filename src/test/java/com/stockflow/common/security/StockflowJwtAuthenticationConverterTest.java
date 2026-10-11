package com.stockflow.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Permissions come from the server-side lookup, never from the token (ADR-0008). */
class StockflowJwtAuthenticationConverterTest {

    private static final PermissionCode PO_READ = PermissionCode.parse("procurement-purchase-orders:READ");

    private final List<Collection<String>> asked = new ArrayList<>();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private StockflowJwtAuthenticationConverter converterGranting(ResolvedAuthorization answer) {
        return new StockflowJwtAuthenticationConverter(roles -> {
            asked.add(List.copyOf(roles));
            return answer;
        });
    }

    private static Jwt token(Object roles, Object permissions) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject(UUID.randomUUID().toString()).claim("preferred_username", "jane");
        if (roles != null) {
            builder.claim("roles", roles);
        }
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        return builder.build();
    }

    private static Set<String> authorities(Collection<? extends GrantedAuthority> granted) {
        Set<String> names = new java.util.HashSet<>();
        granted.forEach(a -> names.add(a.getAuthority()));
        return names;
    }

    @Test
    void permissionsComeFromTheLookupAndAPermissionsClaimIsIgnored() {
        var converter = converterGranting(new ResolvedAuthorization(Set.of(PO_READ), DataScope.ALL));

        var auth = converter.convert(token(List.of("PROCUREMENT_STAFF"), List.of("identity-rbac:APPROVE")));

        assertThat(authorities(auth.getAuthorities()))
                .containsExactlyInAnyOrder("PROCUREMENT_STAFF", "procurement-purchase-orders:READ");
    }

    /** Custom roles exist since SCRUM-455: the lookup, which only knows real roles, decides. */
    @Test
    void customRoleCodesReachTheLookup() {
        var converter = converterGranting(new ResolvedAuthorization(Set.of(PO_READ), DataScope.ALL));

        var auth = converter.convert(token(List.of("PROCUREMENT_STAFF", "SHIFT_LEAD"), null));

        assertThat(asked).containsExactly(List.of("PROCUREMENT_STAFF", "SHIFT_LEAD"));
        assertThat(authorities(auth.getAuthorities()))
                .containsExactlyInAnyOrder("PROCUREMENT_STAFF", "SHIFT_LEAD", "procurement-purchase-orders:READ");
    }

    /** A value not shaped like a role code could pass for a permission authority; it never gets in. */
    @Test
    void malformedRoleValuesAreDroppedBeforeTheLookup() {
        var converter = converterGranting(new ResolvedAuthorization(Set.of(PO_READ), DataScope.ALL));

        var auth = converter.convert(token(List.of("PROCUREMENT_STAFF", "identity-rbac:APPROVE", "lower_case", ""), null));

        assertThat(asked).containsExactly(List.of("PROCUREMENT_STAFF"));
        assertThat(authorities(auth.getAuthorities()))
                .doesNotContain("identity-rbac:APPROVE", "lower_case", "");
    }

    @Test
    void aTokenWithoutRolesGetsNothingAndTheNarrowestScopeWithoutALookup() {
        var converter = converterGranting(new ResolvedAuthorization(Set.of(PO_READ), DataScope.ALL));

        var auth = (StockflowAuthenticationToken) converter.convert(token(null, List.of("identity-rbac:APPROVE")));

        assertThat(asked).isEmpty();
        assertThat(auth.getAuthorities()).isEmpty();
        assertThat(auth.scope()).isEqualTo(DataScope.OWN);
    }

    @Test
    void aLookupThatCannotAnswerRefusesTheRequestInsteadOfGrantingAnything() {
        var converter = new StockflowJwtAuthenticationConverter(roles -> {
            throw new IllegalStateException("redis and postgres are both down");
        });

        assertThatThrownBy(() -> converter.convert(token(List.of("ECOMMERCE_ADMIN"), null)))
                .isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void currentUserTakesTheResolvedScopeNowThatTheTokenHasNoScopeClaim() {
        var converter = converterGranting(new ResolvedAuthorization(Set.of(PO_READ), DataScope.ALL));
        SecurityContextHolder.getContext().setAuthentication(
                converter.convert(token(List.of("PROCUREMENT_STAFF"), null)));

        CurrentUser user = new CurrentUserProvider().current().orElseThrow();

        // Read from the claim this would be OWN, narrowing every scoped query for every user.
        assertThat(user.scope()).isEqualTo(DataScope.ALL);
        assertThat(user.permissions()).containsExactly(PO_READ);
        assertThat(user.hasRole(Role.PROCUREMENT_STAFF)).isTrue();
    }
}

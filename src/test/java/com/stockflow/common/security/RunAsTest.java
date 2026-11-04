package com.stockflow.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link RunAs}: what a background job acting for a user holds, and when it may not act for them at all. */
class RunAsTest {

    private static final UUID USER = UUID.fromString("01a1a000-0000-7000-8000-000000000001");
    private static final UUID HCM = UUID.fromString("01a1a000-0000-7000-8000-0000000000aa");
    private static final PermissionCode READ_ORDERS = PermissionCode.of("sales-orders", Action.READ);

    private final UserAccountLookup accounts = mock(UserAccountLookup.class);
    private final RoleAuthorizationLookup authorizations = mock(RoleAuthorizationLookup.class);
    private final UserWarehouseLookup warehouses = mock(UserWarehouseLookup.class);
    private final RunAs runAs = new RunAs(accounts, authorizations, warehouses, new CurrentUserProvider(),
            Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        DataScopeContext.clear();
    }

    private void account(boolean active, String... roles) {
        when(accounts.account(USER)).thenReturn(Optional.of(
                new UserAccountLookup.UserAccount(USER, "manager.hcm", active, List.of(roles))));
    }

    @Test
    @DisplayName("acts with the user's grants, scope and warehouses as stored now, named as the actor; then puts the "
            + "caller's context back")
    void actsAsTheUserNow() {
        account(true, "WAREHOUSE_MANAGER");
        when(authorizations.resolve(Set.of("WAREHOUSE_MANAGER")))
                .thenReturn(new ResolvedAuthorization(Set.of(READ_ORDERS), DataScope.WAREHOUSE));
        when(warehouses.warehousesOf(USER)).thenReturn(new AssignedWarehouses(Set.of(HCM), Set.of("HCM")));
        SecurityContext callers = SecurityContextHolder.createEmptyContext();
        callers.setAuthentication(new TestingAuthenticationToken("scheduler", null));
        SecurityContextHolder.setContext(callers);

        CurrentUser seen = runAs.call(USER, "assistant-watch", user -> {
            JwtAuthenticationToken token = (JwtAuthenticationToken) SecurityContextHolder.getContext()
                    .getAuthentication();
            assertThat(CurrentUserProvider.actorOf(token.getToken())).isEqualTo("assistant-watch");
            assertThat(CurrentUserProvider.auditName(user)).isEqualTo("manager.hcm via assistant-watch");
            return user;
        });

        assertThat(seen.userId()).isEqualTo(USER);
        assertThat(seen.username()).isEqualTo("manager.hcm");
        assertThat(seen.can("sales-orders", Action.READ)).isTrue();
        assertThat(seen.scope()).isEqualTo(DataScope.WAREHOUSE);
        assertThat(seen.warehouseIds()).containsExactly(HCM);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("scheduler");
    }

    @Test
    @DisplayName("an unknown user, an account that may not sign in, and one with no staff role are refused before "
            + "anything runs")
    void refusesWhoCannotAct() {
        AtomicBoolean ran = new AtomicBoolean();
        when(accounts.account(USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> ran.getAndSet(true)))
                .isInstanceOfSatisfying(RunAs.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(RunAs.Refusal.NO_SUCH_USER));

        account(false, "WAREHOUSE_MANAGER");
        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> ran.getAndSet(true)))
                .isInstanceOfSatisfying(RunAs.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(RunAs.Refusal.INACTIVE));

        account(true);
        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> ran.getAndSet(true)))
                .isInstanceOfSatisfying(RunAs.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(RunAs.Refusal.NO_STAFF_ROLE));
        verify(authorizations, never()).resolve(any());

        account(true, Roles.CUSTOMER);
        when(authorizations.resolve(Set.of(Roles.CUSTOMER)))
                .thenReturn(new ResolvedAuthorization(Set.of(READ_ORDERS), DataScope.OWN));
        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> ran.getAndSet(true)))
                .isInstanceOfSatisfying(RunAs.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(RunAs.Refusal.NO_STAFF_ROLE));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("put back after the refusal").isNull();

        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("not knowing the user's grants is not having them: a lookup that fails refuses")
    void aFailedLookupRefuses() {
        account(true, "WAREHOUSE_MANAGER");
        when(authorizations.resolve(any())).thenThrow(new IllegalStateException("redis and postgres both down"));

        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> user))
                .isInstanceOfSatisfying(RunAs.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(RunAs.Refusal.UNAVAILABLE));
    }

    @Test
    @DisplayName("work that throws still leaves the thread as it found it: no user, the caller's data scope")
    void restoresOnFailure() {
        account(true, "INVENTORY_PLANNER");
        when(authorizations.resolve(any())).thenReturn(new ResolvedAuthorization(Set.of(READ_ORDERS), DataScope.ALL));
        CurrentUser caller = new CurrentUser(UUID.randomUUID(), "caller", Set.of(), Set.of(), DataScope.OWN, Set.of());
        DataScopeContext.set(DataScope.OWN, caller);

        assertThatThrownBy(() -> runAs.call(USER, "assistant-watch", user -> {
            assertThat(DataScopeContext.current()).as("no scope of the caller's leaks in").isEmpty();
            throw new IllegalStateException("the work failed");
        })).hasMessage("the work failed");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(DataScopeContext.current()).hasValueSatisfying(scope -> assertThat(scope.user()).isEqualTo(caller));
    }

    @Test
    @DisplayName("an actor is named in lower case with dashes")
    void namesItsActor() {
        assertThatThrownBy(() -> runAs.call(USER, "Assistant Watch", user -> user))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accounts, never()).account(any());
    }
}

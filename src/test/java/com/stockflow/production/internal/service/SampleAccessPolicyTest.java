package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.PermissionChecker;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SampleAccessPolicyTest {
    private final UUID customerId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final CustomerService customers = mock(CustomerService.class);
    // Exercise the shared permission checker against server authorities, not a permissive mock.
    private final SampleAccessPolicy policy = new SampleAccessPolicy(users, new PermissionChecker(), customers);
    private final SampleRequest sample = SampleRequest.draft(customerId, null, creatorId,
            new SampleSpecification(UUID.randomUUID(), "a".repeat(64), new Sku("CUP-001"), 2, null));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        DataScopeContext.clear();
    }

    @Test
    void anonymousCannotReachAnyOperation() {
        var error = assertThrows(BusinessException.class, () -> policy.require(Action.READ, true));
        assertThat(error.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void roleAloneDoesNotGrantPermission() {
        authenticate(user(creatorId, Role.SALES_STAFF, DataScope.ALL));
        var error = assertThrows(BusinessException.class, () -> policy.require(Action.APPROVE, true));
        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void salesCanActWithGrantedPermissionWithinOwnData() {
        var sales = user(creatorId, Role.SALES_STAFF, DataScope.OWN);
        authenticate(sales, Action.UPDATE);
        assertThat(policy.require(Action.UPDATE, false)).isEqualTo(sales);
        policy.requireOwnership(sales, sample);
    }

    @Test
    void staffOwnScopeCannotAccessAnotherCreatorsRequest() {
        var sales = user(UUID.randomUUID(), Role.SALES_STAFF, DataScope.OWN);
        var error = assertThrows(BusinessException.class, () -> policy.requireOwnership(sales, sample));
        assertThat(error.errorCode()).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
    }

    @ParameterizedTest
    @EnumSource(value = DataScope.class, names = {"TEAM", "WAREHOUSE"})
    void unsupportedStaffScopesFailClosedPendingRealMappings(DataScope scope) {
        assertThatThrownBy(() -> policy.requireOwnership(user(creatorId, Role.SALES_STAFF, scope), sample))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void customerCannotEditDraftEvenIfUpdateWasAccidentallyGranted() {
        var customer = user(UUID.randomUUID(), Role.CUSTOMER, DataScope.ALL);
        authenticate(customer, Action.UPDATE);
        var error = assertThrows(BusinessException.class, () -> policy.require(Action.UPDATE, false));
        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void customerWithAllScopeStillCannotReviewAnotherCustomersSample() {
        var customer = user(UUID.randomUUID(), Role.CUSTOMER, DataScope.ALL);
        authenticate(customer, Action.APPROVE);
        when(customers.findByUserId(customer.userId())).thenReturn(Optional.of(profile(UUID.randomUUID(), customer.userId())));
        policy.require(Action.APPROVE, true);
        assertThatThrownBy(() -> policy.requireOwnership(customer, sample)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void customerOwnershipUsesProfileIdNotUserId() {
        var customer = user(UUID.randomUUID(), Role.CUSTOMER, DataScope.OWN);
        when(customers.findByUserId(customer.userId())).thenReturn(Optional.of(profile(customerId, customer.userId())));
        policy.requireOwnership(customer, sample);
        verify(customers).findByUserId(customer.userId());
    }

    @Test
    void missingCustomerProfileDoesNotWidenScope() {
        var customer = user(customerId, Role.CUSTOMER, DataScope.ALL);
        assertThatThrownBy(() -> policy.requireOwnership(customer, sample)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void nestedNarrowerScopeAndMismatchedPrincipalAreRespected() {
        var staff = user(UUID.randomUUID(), Role.SALES_STAFF, DataScope.ALL);
        DataScopeContext.set(DataScope.OWN, staff);
        assertThatThrownBy(() -> policy.requireOwnership(staff, sample)).isInstanceOf(BusinessException.class);
        DataScopeContext.set(DataScope.ALL, user(UUID.randomUUID(), Role.SALES_STAFF, DataScope.ALL));
        assertThatThrownBy(() -> policy.requireOwnership(staff, sample)).isInstanceOf(BusinessException.class);
    }

    @Test
    void contextCannotWidenTheCurrentPrincipalsScope() {
        UUID id = UUID.randomUUID();
        var current = user(id, Role.SALES_STAFF, DataScope.OWN);
        DataScopeContext.set(DataScope.ALL, user(id, Role.SALES_STAFF, DataScope.ALL));
        assertThatThrownBy(() -> policy.requireOwnership(current, sample)).isInstanceOf(BusinessException.class);
    }

    private CurrentUser user(UUID id, Role role, DataScope scope) {
        return new CurrentUser(id, "test", Set.of(role), Set.of(), scope, Set.of());
    }

    private void authenticate(CurrentUser user, Action... actions) {
        when(users.current()).thenReturn(Optional.of(user));
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(actions)
                .map(action -> new SimpleGrantedAuthority(PermissionCode.of("sample-requests", action).toString())).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.userId(), "unused", authorities));
    }

    private CustomerSummary profile(UUID id, UUID userId) {
        return new CustomerSummary(id, userId, "Test Customer", "test@example.invalid", null, null, "ACTIVE", 0, null);
    }
}

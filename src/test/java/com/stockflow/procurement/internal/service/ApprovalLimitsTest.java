package com.stockflow.procurement.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.repository.ApprovalLimitRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SCRUM-124 rules 1, 3 and 4 as one decision, and the lookup of a person's limit through their roles. */
class ApprovalLimitsTest {

    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final Currency USD = Currency.getInstance("USD");

    private final IdentityService identity = mock(IdentityService.class);
    private final ApprovalLimitRepository rows = mock(ApprovalLimitRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final ApprovalLimits limits = new ApprovalLimits(identity, rows, orders);

    private final UUID holder = UUID.randomUUID();
    private final UUID supplier = UUID.randomUUID();
    private final PurchaseOrderId order = PurchaseOrderId.newId();
    private final List<UUID> roles = List.of(UUID.randomUUID(), UUID.randomUUID());

    private ApprovalLimit configured(Currency currency, long amount) {
        ApprovalLimit limit = new ApprovalLimit(UUID.randomUUID(), roles.get(1),
                new Money(BigDecimal.valueOf(amount), currency));
        when(identity.roleIdsOf(holder)).thenReturn(roles);
        when(rows.highestLimit(roles, currency, TODAY)).thenReturn(Optional.of(limit));
        return limit;
    }

    @Test
    @DisplayName("limitOf: the limit row the repository picks among the person's roles, for the currency and the day")
    void limitIsLookedUpThroughTheRoles() {
        ApprovalLimit limit = configured(Money.VND, 1_000);
        assertThat(limits.limitOf(holder, Money.VND, TODAY)).contains(limit);
        assertThat(limits.limitOf(holder, USD, TODAY)).as("another currency has no row").isEmpty();
    }

    @Test
    @DisplayName("no limit configured is no limit, not zero: nothing is auto-approved, and nothing else is read")
    void noLimitMeansNoAutoApproval() {
        when(identity.roleIdsOf(holder)).thenReturn(roles);
        assertThat(limits.autoApprovalLimit(order, holder, supplier, Money.vnd(1), NOW)).isEmpty();
        verify(orders, never()).autoApprovedTotal(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("within the limit, inclusive, and with nothing else approved lately: the limit that allowed it")
    void withinLimit() {
        ApprovalLimit limit = configured(Money.VND, 1_000);
        when(orders.autoApprovedTotal(holder, supplier, Money.VND, NOW.minus(Duration.ofDays(7)), order))
                .thenReturn(Money.zero(Money.VND));
        assertThat(limits.autoApprovalLimit(order, holder, supplier, Money.vnd(1_000), NOW)).contains(limit);
        assertThat(limits.autoApprovalLimit(order, holder, supplier, Money.vnd(1_001), NOW)).isEmpty();
    }

    @Test
    @DisplayName("the window is the 7 days ending now, in the order's currency")
    void windowAndCurrency() {
        ApprovalLimit limit = configured(USD, 500);
        when(orders.autoApprovedTotal(holder, supplier, USD, NOW.minus(Duration.ofDays(7)), order))
                .thenReturn(new Money(BigDecimal.valueOf(100), USD));
        assertThat(limits.autoApprovalLimit(order, holder, supplier, new Money(BigDecimal.valueOf(400), USD), NOW))
                .contains(limit);
        assertThat(limits.autoApprovalLimit(order, holder, supplier, new Money(new BigDecimal("400.01"), USD), NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("an order that was rejected before is not auto-approved and no limit is even looked at")
    void rejectedBefore() {
        configured(Money.VND, 1_000_000);
        when(orders.hasBeenRejected(order)).thenReturn(true);
        assertThat(limits.autoApprovalLimit(order, holder, supplier, Money.vnd(1), NOW)).isEmpty();
        verify(rows, never()).highestLimit(any(), any(), any());
    }
}

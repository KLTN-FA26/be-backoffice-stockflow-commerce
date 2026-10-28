package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** SCRUM-190: which earlier order makes a new checkout suspect. Pure matching; the window and the HTTP 409 are tested elsewhere. */
class DuplicateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T04:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID DESIGN = UUID.randomUUID();

    private final OrderRepository repository = mock(OrderRepository.class);
    private DuplicateOrderService service;

    @BeforeEach
    void aServiceWithADayWindow() {
        service = new DuplicateOrderService(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
        when(repository.findByRequestId(any())).thenReturn(Optional.empty());
    }

    private static Order order(long sequence, Instant placedAt, UUID design, Object... skuAndQuantity) {
        var lines = new java.util.ArrayList<com.stockflow.order.internal.domain.OrderLine>();
        for (int i = 0; i < skuAndQuantity.length; i += 2) {
            lines.add(Order.line(new Sku((String) skuAndQuantity[i]), (Integer) skuAndQuantity[i + 1],
                    Money.vnd(1000), design));
        }
        return Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 28), sequence), CUSTOMER, UUID.randomUUID(), lines,
                placedAt);
    }

    private void earlier(Order... orders) {
        when(repository.findLiveSince(eq(CUSTOMER), eq(NOW.minus(Duration.ofHours(24))))).thenReturn(List.of(orders));
    }

    private Optional<DuplicateOrders.Earlier> check(DuplicateOrders.Line... lines) {
        return service.findSuspected(CUSTOMER, UUID.randomUUID(), List.of(lines));
    }

    private static DuplicateOrders.Line line(String sku, int quantity) {
        return new DuplicateOrders.Line(sku, quantity, null);
    }

    @Test
    @DisplayName("the same lines in another order are a match, and the match names the earlier order")
    void sameLinesMatch() {
        Order earlier = order(1, NOW.minusSeconds(600), null, "A-1", 2, "B-1", 5);
        earlier(earlier);
        var match = check(line("B-1", 5), line("A-1", 2));
        assertThat(match).isPresent();
        assertThat(match.get().orderId()).isEqualTo(earlier.id().value());
        assertThat(match.get().orderNumber()).isEqualTo("SO-20261028-000001");
        assertThat(match.get().placedAt()).isEqualTo(NOW.minusSeconds(600));
    }

    @Test
    @DisplayName("a different quantity, an extra line or a missing line is not a match")
    void differentContentDoesNotMatch() {
        earlier(order(1, NOW.minusSeconds(600), null, "A-1", 2, "B-1", 5));
        assertThat(check(line("A-1", 2), line("B-1", 4))).isEmpty();
        assertThat(check(line("A-1", 2))).isEmpty();
        assertThat(check(line("A-1", 2), line("B-1", 5), line("C-1", 1))).isEmpty();
        assertThat(check(line("A-1", 2), line("C-1", 5))).isEmpty();
    }

    @Test
    @DisplayName("the same SKU on two lines counts as its total, whichever side splits it")
    void aSplitSkuEqualsItsTotal() {
        earlier(order(1, NOW.minusSeconds(600), null, "A-1", 1, "A-1", 1));
        assertThat(check(line("a-1", 2))).isPresent();
        earlier(order(2, NOW.minusSeconds(600), null, "A-1", 2));
        assertThat(check(line("A-1", 1), line("A-1", 1))).isPresent();
    }

    @Test
    @DisplayName("a design line is not the same as stock goods of the same SKU")
    void aDesignIsNotStock() {
        earlier(order(1, NOW.minusSeconds(600), DESIGN, "A-1", 2));
        assertThat(check(line("A-1", 2))).isEmpty();
        assertThat(service.findSuspected(CUSTOMER, UUID.randomUUID(),
                List.of(new DuplicateOrders.Line("A-1", 2, DESIGN)))).isPresent();
    }

    @Test
    @DisplayName("of several matches the newest is named")
    void theNewestIsNamed() {
        Order newest = order(2, NOW.minusSeconds(60), null, "A-1", 1);
        earlier(newest, order(1, NOW.minusSeconds(3600), null, "A-1", 1));
        assertThat(check(line("A-1", 1)).orElseThrow().orderId()).isEqualTo(newest.id().value());
    }

    @Test
    @DisplayName("the window ends where the repository is asked: orders older than the window are never fetched")
    void theWindowIsTheRepositorysSince() {
        earlier();
        assertThat(check(line("A-1", 1))).isEmpty();
        verify(repository).findLiveSince(CUSTOMER, NOW.minus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("a request id that already produced an order is a retry, not a duplicate")
    void aRetryIsNotADuplicate() {
        UUID request = UUID.randomUUID();
        when(repository.findByRequestId(request)).thenReturn(Optional.of(order(1, NOW, null, "A-1", 1)));
        assertThat(service.findSuspected(CUSTOMER, request, List.of(line("A-1", 1)))).isEmpty();
    }

    @Test
    @DisplayName("a zero window switches the check off without touching the orders")
    void zeroSwitchesItOff() {
        var off = new DuplicateOrderService(repository, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO);
        assertThat(off.findSuspected(CUSTOMER, UUID.randomUUID(), List.of(line("A-1", 1)))).isEmpty();
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("a negative window is a configuration error")
    void aNegativeWindowIsRefused() {
        assertThatThrownBy(() -> new DuplicateOrderService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}

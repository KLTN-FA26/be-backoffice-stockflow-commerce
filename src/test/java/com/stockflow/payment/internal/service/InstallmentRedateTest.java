package com.stockflow.payment.internal.service;

import com.stockflow.contracts.InstallmentDue;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-04 (audit L-1, M-3; decision Q-3): an installment paid after delivery follows the day the order counts as
 * delivered in full - reached once, it is moved when that day moves (a delivery recorded after a later one, a return
 * closed after the last delivery), never left on a stale day and never reached twice; announced again when moved.
 */
class InstallmentRedateTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    /** Shipment Y, delivered on the 5th but recorded last. */
    private static final Instant EARLY = Instant.parse("2026-11-05T03:00:00Z");
    /** Shipment X, delivered on the 20th. */
    private static final Instant LATE = Instant.parse("2026-11-20T03:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final List<Object> published = new ArrayList<>();
    private final InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class), orders,
            new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneOffset.UTC), published::add);

    private static Installment after(int seq, UUID batch, int days) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-20261101-000001-1", "SO-20261101-000001", seq, "After " + seq, InstallmentMilestone.AFTER_DELIVERY,
                batch, batch == null ? null : 1, BigDecimal.valueOf(1_000_000), "VND", days);
    }

    private void book(List<Installment> installments) {
        when(book.lockOfOrder(ORDER)).thenReturn(installments);
        when(book.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void orderSays(Map<UUID, Instant> batches, Instant whole) {
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(batches);
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.ofNullable(whole));
    }

    private long dues() {
        return published.stream().filter(InstallmentDue.class::isInstance).count();
    }

    @Test
    @DisplayName("reached from a stale day, it is moved to the order's day - due by the later day plus its days - and "
            + "announced again; heard again, nothing more")
    void movedToTheOrdersDay() {
        Installment whole = after(2, null, 30);
        book(List.of(whole));
        orderSays(Map.of(), EARLY);
        service.delivered(ORDER, EARLY, false);
        assertThat(whole.dueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(dues()).isEqualTo(1);

        // The order now counts as delivered in full on the 20th - the later delivery recorded, or a return closed.
        orderSays(Map.of(), LATE);
        service.delivered(ORDER, LATE, false);

        assertThat(whole.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(whole.reachedAt()).isEqualTo(LATE);
        assertThat(whole.dueDate()).as("30 days from the last delivery, not the first recorded")
                .isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(whole.overdue(LocalDate.of(2026, 12, 10))).as("not overdue early").isFalse();
        assertThat(dues()).as("the transfer card hears the new day").isEqualTo(2);

        service.delivered(ORDER, LATE, false);
        assertThat(dues()).as("never reached twice").isEqualTo(2);
    }

    @Test
    @DisplayName("a batch's installment follows its batch's day; partly paid keeps what was paid; paid is left alone")
    void batchPartlyPaidAndPaid() {
        Installment partly = after(2, BATCH, 10);
        Installment paid = after(3, BATCH, 10);
        book(List.of(partly, paid));
        orderSays(Map.of(BATCH, EARLY), null);
        service.delivered(ORDER, EARLY, false);
        partly.pay(BigDecimal.valueOf(400_000), EARLY);
        paid.pay(BigDecimal.valueOf(1_000_000), EARLY);

        orderSays(Map.of(BATCH, LATE), null);
        service.delivered(ORDER, LATE, false);

        assertThat(partly.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(partly.paidAmount()).isEqualByComparingTo("400000");
        assertThat(partly.dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(paid.reachedAt()).as("paid in full: its day decides nothing").isEqualTo(EARLY);
    }

    @Test
    @DisplayName("a path that is not a correction never takes a milestone back when the order has no day yet")
    void noDayIsNoNews() {
        Installment whole = after(2, null, 30);
        book(List.of(whole));
        orderSays(Map.of(), EARLY);
        service.delivered(ORDER, EARLY, false);

        orderSays(Map.of(), null);
        service.delivered(ORDER, LATE, false);
        service.shipmentReturnSettled(ORDER, null);

        assertThat(whole.reachedAt()).isEqualTo(EARLY);
        assertThat(whole.status()).isEqualTo(InstallmentStatus.DUE);
    }

    @Test
    @DisplayName("Installment.redate: only after delivery, reached, still owing")
    void redateRules() {
        Installment notReached = after(2, null, 30);
        assertThat(notReached.redate(LATE, LocalDate.of(2026, 11, 20))).isFalse();
        Installment reached = after(3, null, 30);
        reached.reach(EARLY, LocalDate.of(2026, 11, 5));
        assertThat(reached.redate(EARLY, LocalDate.of(2026, 11, 5))).as("same day: nothing changes").isFalse();
        assertThat(reached.redate(LATE, LocalDate.of(2026, 11, 20))).isTrue();
        Installment onSigning = Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING, null, null, BigDecimal.TEN, "VND", 0);
        onSigning.reach(EARLY, LocalDate.of(2026, 11, 5));
        assertThat(onSigning.redate(LATE, LocalDate.of(2026, 11, 20))).isFalse();
    }
}

package com.stockflow.payment.internal.service;

import com.stockflow.contracts.InstallmentDue;
import com.stockflow.contracts.ShipmentReturnSettled;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;

import java.lang.reflect.Modifier;
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
 * WF-05 BE-02 (audit M-1; decisions Q-3, Q-19): after the units of a shipment the carrier brought back are settled, the
 * installments paid after delivery fall due - every amount still owed gets a due date (invariant M-I5). Scenario A: part
 * of an order brought back; B: a delivery schedule, batch 1 brought back, batches 2 and 3 delivered; C: everything
 * brought back, a plate fee left owing.
 */
class ShipmentReturnSettledListenerTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();
    private static final UUID BATCH_3 = UUID.randomUUID();
    private static final Instant DELIVERED = Instant.parse("2026-11-01T03:00:00Z");
    /** The warehouse confirmed the return receipt (Q-7): later than the last delivery. */
    private static final Instant GOODS_BACK = Instant.parse("2026-11-03T03:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final List<Object> published = new ArrayList<>();
    private final InstallmentService installments = new InstallmentService(book, mock(InstallmentSearch.class),
            orders, new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneOffset.UTC), published::add);
    private final ShipmentReturnSettledListener listener = new ShipmentReturnSettledListener(installments);

    private static Installment after(int seq, UUID batch, Integer batchSeq, long amount) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-20261101-000001-1", "SO-20261101-000001", seq, "After " + seq, InstallmentMilestone.AFTER_DELIVERY,
                batch, batchSeq, BigDecimal.valueOf(amount), "VND", 30);
    }

    private void book(List<Installment> installments) {
        when(book.lockOfOrder(ORDER)).thenReturn(installments);
        when(book.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void orderSays(Map<UUID, Instant> batches, Instant whole) {
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(batches);
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.ofNullable(whole));
    }

    private static ShipmentReturnSettled settled(UUID batch, boolean nothingDelivered, boolean nothingMoreComing) {
        return new ShipmentReturnSettled(ORDER, "SO-20261101-000001", UUID.randomUUID(), UUID.randomUUID(), batch,
                UUID.randomUUID(), ShipmentReturnSettled.Cause.CARRIER_RETURN, GOODS_BACK, GOODS_BACK,
                nothingDelivered, nothingMoreComing);
    }

    @Test
    @DisplayName("A: part brought back - the order is delivered in full on the later of the last delivery and the goods "
            + "back (X-3), and what it still owes after delivery falls due from it")
    void partialReturn() {
        Installment rest = after(2, null, null, 700_000);
        book(List.of(rest));
        orderSays(Map.of(), GOODS_BACK);

        listener.on(settled(null, false, true));

        assertThat(rest.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(rest.reachedAt()).isEqualTo(GOODS_BACK);
        assertThat(rest.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(published).singleElement().isInstanceOf(InstallmentDue.class);
    }

    @Test
    @DisplayName("B: batch 1 brought back, batches 2 and 3 delivered - batch 1's installment, its batch never delivered "
            + "in full, falls due with the whole order; the others with their batches")
    void scheduleBatchOneBack() {
        Installment first = after(2, BATCH_1, 1, 100_000);
        Installment second = after(3, BATCH_2, 2, 300_000);
        Installment third = after(4, BATCH_3, 3, 300_000);
        book(List.of(first, second, third));
        orderSays(Map.of(BATCH_2, DELIVERED, BATCH_3, DELIVERED), GOODS_BACK);

        listener.on(settled(BATCH_1, false, true));

        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(second.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(third.dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(List.of(first, second, third)).allSatisfy(i -> assertThat(i.status())
                .isEqualTo(InstallmentStatus.DUE));
    }

    @Test
    @DisplayName("C: everything brought back - the order has no delivered-in-full day at all; what is still owed after "
            + "delivery (the plate fee Q-19 keeps) falls due from the goods back plus the contract's days (Q-3)")
    void wholeReturn() {
        Installment plateFeeLeft = after(2, null, null, 55_000);
        book(List.of(plateFeeLeft));
        orderSays(Map.of(), null);

        listener.on(settled(null, true, true));

        assertThat(plateFeeLeft.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(plateFeeLeft.reachedAt()).isEqualTo(GOODS_BACK);
        assertThat(plateFeeLeft.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3));
    }

    @Test
    @DisplayName("nothing delivered yet but more to come: nothing falls due now - the next delivery or closure decides")
    void moreToCome() {
        Installment rest = after(2, null, null, 700_000);
        book(List.of(rest));
        orderSays(Map.of(), null);

        listener.on(settled(null, true, false));

        assertThat(rest.status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("idempotent: heard twice, or replayed, nothing changes and nobody is told again")
    void heardTwice() {
        Installment rest = after(2, null, null, 700_000);
        book(List.of(rest));
        orderSays(Map.of(), GOODS_BACK);
        ShipmentReturnSettled event = settled(null, false, true);

        listener.on(event);
        listener.on(event);

        assertThat(published).hasSize(1);
        assertThat(rest.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3));
    }

    @Test
    @DisplayName("an @ApplicationModuleListener, public, so the proxy runs it after commit in a transaction of its own")
    void proxied() throws Exception {
        var on = ShipmentReturnSettledListener.class.getMethod("on", ShipmentReturnSettled.class);
        assertThat(on.isAnnotationPresent(ApplicationModuleListener.class)).isTrue();
        assertThat(Modifier.isPublic(on.getModifiers())).isTrue();
    }
}

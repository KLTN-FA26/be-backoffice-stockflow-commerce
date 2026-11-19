package com.stockflow.payment.internal.service;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 GH-3 (kltn-docs 09 §4.3 step 5, 15 §4.1, E17): a delivery corrected to a failure takes back the milestone of
 * the installments paid after it - not due, no due day, so neither overdue nor counted overdue by the credit check -
 * and the goods delivered again make them due from the new day. Driven against the same facts {@code delivered} reads:
 * the batches the order has delivered in full, and when the whole order was.
 */
class InstallmentDeliveryCorrectionTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();
    /** The carrier's wrong report. */
    private static final Instant WRONG_DELIVERY = Instant.parse("2026-12-02T08:00:00Z");
    /** The delivery made again a week later. */
    private static final Instant REDELIVERY = Instant.parse("2026-12-09T08:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class), orders,
            new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(Instant.parse("2026-12-03T03:00:00Z"), ZoneOffset.UTC));

    private static Installment of(int seq, UUID batch, long amount, int days) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-20261110-000001-1", "SO-20261110-000001", seq, "Part " + seq, InstallmentMilestone.AFTER_DELIVERY,
                batch, batch == null ? null : batch == BATCH_1 ? 1 : 2, BigDecimal.valueOf(amount), "VND", days);
    }

    private void book(List<Installment> installments) {
        when(book.lockOfOrder(ORDER)).thenReturn(installments);
        when(book.ofOrder(ORDER)).thenReturn(installments);
        when(book.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void orderSays(Map<UUID, Instant> batches, Instant whole) {
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(batches);
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.ofNullable(whole));
    }

    @Test
    @DisplayName("GH-3: batch 1 corrected to a failure - its installment goes back to NOT_DUE with no due day, is never "
            + "overdue; delivered again a week later it is due from the new day")
    void backToNotDueThenDueFromTheRedelivery() {
        Installment first = of(2, BATCH_1, 75_600_000, 30);
        Installment second = of(3, BATCH_2, 300, 10);
        book(List.of(first, second));
        orderSays(Map.of(BATCH_1, WRONG_DELIVERY), null);
        service.delivered(ORDER, WRONG_DELIVERY, false);
        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(first.overdue(LocalDate.of(2027, 1, 5))).as("as it was: overdue 30 days after the wrong delivery")
                .isTrue();

        // The warehouse manager corrects it: the order no longer has batch 1 delivered.
        orderSays(Map.of(), null);
        service.deliveryCorrected(ORDER);

        assertThat(first.status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(first.reached()).isFalse();
        assertThat(first.dueDate()).isNull();
        assertThat(first.overdue(LocalDate.of(2027, 1, 5))).as("not overdue for goods never received").isFalse();
        assertThat(first.overdue(LocalDate.of(2027, 6, 1))).isFalse();
        assertThat(second.status()).as("batch 2 was never reached").isEqualTo(InstallmentStatus.NOT_DUE);

        // A week later the carrier delivers again; the order's own OrderDelivered reaches it from that day.
        orderSays(Map.of(BATCH_1, REDELIVERY), null);
        service.delivered(ORDER, REDELIVERY, false);

        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.reachedAt()).isEqualTo(REDELIVERY);
        assertThat(first.dueDate()).as("30 days from the real delivery").isEqualTo(LocalDate.of(2027, 1, 8));
        assertThat(first.overdue(LocalDate.of(2027, 1, 5))).isFalse();
    }

    @Test
    @DisplayName("GH-3: a partly paid installment keeps what was paid - PARTIALLY_PAID, no due day - and a paid one is "
            + "left alone")
    void partlyPaidKeepsItsMoney() {
        Installment partly = of(2, BATCH_1, 1_000, 30);
        Installment paid = of(3, BATCH_1, 500, 30);
        book(List.of(partly, paid));
        orderSays(Map.of(BATCH_1, WRONG_DELIVERY), null);
        service.delivered(ORDER, WRONG_DELIVERY, false);
        partly.pay(BigDecimal.valueOf(400), WRONG_DELIVERY);
        paid.pay(BigDecimal.valueOf(500), WRONG_DELIVERY);

        orderSays(Map.of(), null);
        service.deliveryCorrected(ORDER);

        assertThat(partly.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(partly.paidAmount()).isEqualByComparingTo("400");
        assertThat(partly.outstanding()).isEqualByComparingTo("600");
        assertThat(partly.dueDate()).isNull();
        assertThat(partly.overdue(LocalDate.of(2027, 3, 1))).isFalse();
        assertThat(paid.status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(paid.reachedAt()).as("paid in full: its milestone is kept").isEqualTo(WRONG_DELIVERY);

        orderSays(Map.of(BATCH_1, REDELIVERY), null);
        service.delivered(ORDER, REDELIVERY, false);
        assertThat(partly.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(partly.dueDate()).isEqualTo(LocalDate.of(2027, 1, 8));
    }

    @Test
    @DisplayName("GH-3: the installment tied to no batch, due from the whole order, is taken back when one of its "
            + "shipments is corrected; one of another batch still delivered keeps its day")
    void theUntiedOneFollowsTheWholeOrder() {
        Installment first = of(2, BATCH_1, 300, 10);
        Installment untied = of(4, null, 100, 20);
        book(List.of(first, untied));
        orderSays(Map.of(BATCH_1, WRONG_DELIVERY, BATCH_2, REDELIVERY), REDELIVERY);
        service.delivered(ORDER, REDELIVERY, false);
        assertThat(untied.status()).isEqualTo(InstallmentStatus.DUE);

        // Batch 2's shipment corrected: batch 1 is still delivered, the whole order no longer is.
        orderSays(Map.of(BATCH_1, WRONG_DELIVERY), null);
        service.deliveryCorrected(ORDER);

        assertThat(untied.status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(untied.dueDate()).isNull();
        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.reachedAt()).isEqualTo(WRONG_DELIVERY);
    }

    @Test
    @DisplayName("GH-3: heard after the redelivery's own event, the correction moves the due day to the redelivery; "
            + "heard twice, it changes nothing more")
    void heardLateOrTwice() {
        Installment first = of(2, BATCH_1, 300, 30);
        book(List.of(first));
        orderSays(Map.of(BATCH_1, WRONG_DELIVERY), null);
        service.delivered(ORDER, WRONG_DELIVERY, false);
        // The redelivery's event came first: reached already, it moved nothing.
        orderSays(Map.of(BATCH_1, REDELIVERY), null);
        service.delivered(ORDER, REDELIVERY, false);
        assertThat(first.reachedAt()).isEqualTo(WRONG_DELIVERY);

        service.deliveryCorrected(ORDER);
        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.reachedAt()).isEqualTo(REDELIVERY);
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2027, 1, 8));

        clearInvocations(book);
        service.deliveryCorrected(ORDER);
        verify(book, never()).save(any());
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2027, 1, 8));
    }

    @Test
    @DisplayName("only an installment after delivery loses its milestone; nothing reached, nothing to take back")
    void onlyAfterDelivery() {
        Installment signing = Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING, null, null, BigDecimal.TEN, "VND", 0);
        signing.reach(WRONG_DELIVERY, LocalDate.of(2026, 12, 2));
        assertThat(signing.withdrawMilestone()).isFalse();
        assertThat(signing.status()).isEqualTo(InstallmentStatus.DUE);
        Installment notReached = of(2, BATCH_1, 300, 10);
        assertThat(notReached.withdrawMilestone()).isFalse();
        assertThat(notReached.status()).isEqualTo(InstallmentStatus.NOT_DUE);
    }
}

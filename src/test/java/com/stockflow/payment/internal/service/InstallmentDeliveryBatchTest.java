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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 4B-8 (kltn-docs 15 §4.1, 17 §4.5) and review fix R3: an order delivered batch by batch owes batch by batch. Releasing
 * a batch makes due what is paid before delivery for it (and what is tied to no batch); the money due at a batch's
 * handover is that batch's; an installment after delivery tied to a batch falls due when that batch is delivered in
 * full, dated from its last delivery, while one tied to no batch waits for the whole order.
 */
class InstallmentDeliveryBatchTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();
    private static final Instant BATCH_1_DELIVERED = Instant.parse("2026-12-02T08:00:00Z");
    private static final Instant ALL_DELIVERED = Instant.parse("2026-12-20T08:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class), orders,
            new CompanyBankProperties("Bank", "0001", "StockFlow"),
            Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC));

    private static Installment of(int seq, InstallmentMilestone milestone, UUID batch, long amount, int days) {
        // An installment paid before delivery has no days to pay (it is due when its goods leave).
        Integer dueDays = milestone == InstallmentMilestone.BEFORE_DELIVERY ? null : days;
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), ORDER, UUID.randomUUID(),
                "HD-20261110-000001-1", "SO-20261110-000001", seq, "Part " + seq, milestone, batch,
                batch == null ? null : batch == BATCH_1 ? 1 : 2, BigDecimal.valueOf(amount), "VND", dueDays);
    }

    private void book(List<Installment> installments) {
        when(book.lockOfOrder(ORDER)).thenReturn(installments);
        when(book.ofOrder(ORDER)).thenReturn(installments);
        when(book.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("R3: batch 1 delivered in full makes batch 1's AFTER_DELIVERY installment due from that day; batch 2's "
            + "and the untied one wait")
    void afterDeliveryFallsDueBatchByBatch() {
        Installment first = of(2, InstallmentMilestone.AFTER_DELIVERY, BATCH_1, 300, 10);
        Installment second = of(3, InstallmentMilestone.AFTER_DELIVERY, BATCH_2, 300, 10);
        Installment untied = of(4, InstallmentMilestone.AFTER_DELIVERY, null, 100, 30);
        book(List.of(first, second, untied));
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(Map.of(BATCH_1, BATCH_1_DELIVERED));
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.empty());

        service.delivered(ORDER, Instant.parse("2026-12-03T00:00:00Z"), false);

        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.dueDate()).as("dated from the batch's last delivery, not from the event")
                .isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(second.status()).as("batch 2 still travels").isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(untied.status()).as("tied to no batch: the whole order").isEqualTo(InstallmentStatus.NOT_DUE);
    }

    @Test
    @DisplayName("R3: the whole order delivered makes the untied one due from that day; a batch-tied one without a "
            + "batch date of its own counts from it too")
    void theUntiedOneWaitsForTheWholeOrder() {
        Installment second = of(3, InstallmentMilestone.AFTER_DELIVERY, BATCH_2, 300, 10);
        Installment untied = of(4, InstallmentMilestone.AFTER_DELIVERY, null, 100, 30);
        Installment before = of(1, InstallmentMilestone.BEFORE_DELIVERY, null, 100, 0);
        book(List.of(before, second, untied));
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(Map.of(BATCH_1, BATCH_1_DELIVERED));
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.of(ALL_DELIVERED));

        service.delivered(ORDER, ALL_DELIVERED, false);

        assertThat(untied.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(untied.dueDate()).isEqualTo(LocalDate.of(2027, 1, 19));
        assertThat(second.dueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(before.status()).as("not an after-delivery installment").isEqualTo(InstallmentStatus.NOT_DUE);
    }

    @Test
    @DisplayName("nothing delivered yet: nothing falls due")
    void nothingDelivered() {
        Installment first = of(2, InstallmentMilestone.AFTER_DELIVERY, BATCH_1, 300, 10);
        book(List.of(first));
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(Map.of());
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.empty());

        service.delivered(ORDER, ALL_DELIVERED, false);

        assertThat(first.status()).isEqualTo(InstallmentStatus.NOT_DUE);
    }

    @Test
    @DisplayName("4B-8: releasing batch 1 makes due its BEFORE_DELIVERY installment and the untied one, not batch 2's; "
            + "released whole (no batch), every one")
    void releasedBatchByBatch() {
        Installment first = of(2, InstallmentMilestone.BEFORE_DELIVERY, BATCH_1, 300, 0);
        Installment second = of(3, InstallmentMilestone.BEFORE_DELIVERY, BATCH_2, 300, 0);
        Installment untied = of(4, InstallmentMilestone.BEFORE_DELIVERY, null, 100, 0);
        Installment after = of(5, InstallmentMilestone.AFTER_DELIVERY, BATCH_1, 100, 10);
        book(List.of(first, second, untied, after));

        service.released(ORDER, BATCH_1_DELIVERED, BATCH_1);

        assertThat(first.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(first.dueDate()).as("paid before delivery has no due day").isNull();
        assertThat(untied.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(second.status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(after.status()).isEqualTo(InstallmentStatus.NOT_DUE);

        service.released(ORDER, ALL_DELIVERED);
        assertThat(second.status()).as("an order released whole").isEqualTo(InstallmentStatus.DUE);
    }

    @Test
    @DisplayName("4B-8: due before a batch's handover - on signing, plus before delivery tied to it or to none; another "
            + "batch's, one after delivery and a cancelled one are not")
    void dueBeforeHandoverOfABatch() {
        Installment signing = of(1, InstallmentMilestone.ON_SIGNING, null, 100, 0);
        Installment first = of(2, InstallmentMilestone.BEFORE_DELIVERY, BATCH_1, 300, 0);
        Installment second = of(3, InstallmentMilestone.BEFORE_DELIVERY, BATCH_2, 400, 0);
        Installment untied = of(4, InstallmentMilestone.BEFORE_DELIVERY, null, 50, 0);
        Installment after = of(5, InstallmentMilestone.AFTER_DELIVERY, BATCH_1, 700, 10);
        Installment cancelled = of(6, InstallmentMilestone.BEFORE_DELIVERY, BATCH_1, 900, 0);
        cancelled.cancel(BATCH_1_DELIVERED);
        book(List.of(signing, first, second, untied, after, cancelled));

        assertThat(service.dueBeforeHandover(ORDER, BATCH_1)).isEqualByComparingTo("450");
        assertThat(service.dueBeforeHandover(ORDER, BATCH_2)).isEqualByComparingTo("550");
        assertThat(service.dueBeforeHandover(ORDER, null)).as("the order as a whole").isEqualByComparingTo("850");
    }
}

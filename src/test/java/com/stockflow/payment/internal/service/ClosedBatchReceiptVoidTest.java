package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.HandedOverGoods;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.OrderSettlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit r3 LM-3, the void path: a receipt recorded before a closure of the rest on an installment before delivery of a
 * batch the closure closed is not voided - voided, the installment would owe again with no milestone, no due day and
 * no gate. {@code tg_payment_receipt_void} states it again.
 */
class ClosedBatchReceiptVoidTest {

    private static final Instant AT = Instant.parse("2026-11-24T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final UUID BATCH_1 = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();

    private final UUID contractId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    private Installment before(int seq, UUID batch, long amount) {
        return Installment.open(contractId, UUID.randomUUID(), orderId, customerId, "HD-1", "SO-1", seq,
                "Before batch " + seq, InstallmentMilestone.BEFORE_DELIVERY, batch, seq, BigDecimal.valueOf(amount),
                "VND", null);
    }

    @Test
    @DisplayName("an early receipt on batch 2's installment, the batch closed after it: not voided; one recorded after "
            + "the closure, on a batch that left, or on an order never closed: the rule does not apply")
    void closedBatchReceiptsStay() {
        Installment first = before(1, BATCH_1, 510_000);
        first.reach(AT.minusSeconds(864_000), DAY.minusDays(10));
        first.pay(BigDecimal.valueOf(510_000), AT.minusSeconds(860_000));
        Installment second = before(2, BATCH_2, 510_000);
        second.pay(BigDecimal.valueOf(20_000), AT.minusSeconds(3_600));
        HandedOverGoods batchOneLeft = new HandedOverGoods(true, Set.of(BATCH_1));
        Instant recorded = AT.minusSeconds(3_600);

        assertThat(PaymentReceiptService.closedBatchSettled(second, batchOneLeft, List.of(), recorded))
                .as("no closure yet").isFalse();

        OrderSettlement closure = OrderSettlement.closeRemainder(List.of(first, second), UUID.randomUUID(),
                BigDecimal.valueOf(450_000), OrderSettlement.Fee.none(), UUID.randomUUID(), AT, DAY, Set.of(BATCH_1),
                BigDecimal.ZERO, Set.of()).settlement();

        assertThat(PaymentReceiptService.closedBatchSettled(second, batchOneLeft, List.of(closure), recorded)).isTrue();
        assertThat(PaymentReceiptService.closedBatchSettled(second, batchOneLeft, List.of(closure),
                AT.plusSeconds(60))).as("recorded after the closure: it counted on nothing").isFalse();
        assertThat(PaymentReceiptService.closedBatchSettled(first, batchOneLeft, List.of(closure), recorded))
                .as("batch 1 left: Q-20's rule, not this one").isFalse();
        assertThat(PaymentReceiptService.closedBatchSettled(second, new HandedOverGoods(true, Set.of(BATCH_1, BATCH_2)),
                List.of(closure), recorded)).as("its batch left").isFalse();
    }
}

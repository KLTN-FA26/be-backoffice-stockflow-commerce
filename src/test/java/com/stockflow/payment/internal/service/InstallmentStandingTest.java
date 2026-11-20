package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an order's installments come to (WF-02 re-check round 3, B6): a cancelled order said "0 paid" next to a refund of
 * the money the customer had paid, because the cancelled installments were left out of the money too, not only out of
 * the status.
 */
class InstallmentStandingTest {

    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private final UUID order = UUID.randomUUID();

    private Installment installment(InstallmentMilestone milestone, long amount) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), order, UUID.randomUUID(), "HD-1", "SO-1", 1,
                "Đặt cọc khi ký", milestone, null, null, BigDecimal.valueOf(amount), "VND", 3);
    }

    @Test
    @DisplayName("every installment cancelled with its order: nothing owed, and what was paid still reads as paid")
    void cancelledOrderKeepsWhatWasPaid() {
        Installment deposit = installment(InstallmentMilestone.ON_SIGNING, 22_680_000);
        deposit.reach(AT, DAY);
        deposit.pay(BigDecimal.valueOf(10_000_000), AT);
        Installment rest = installment(InstallmentMilestone.AFTER_DELIVERY, 52_920_000);
        deposit.cancel(AT);
        rest.cancel(AT);

        InstallmentStanding standing = InstallmentService.standingOf(order, List.of(deposit, rest));

        assertThat(standing.total()).isEqualByComparingTo("0");
        assertThat(standing.paid()).isEqualByComparingTo("10000000");
        assertThat(standing.paymentStatus()).as("no status for a contract with nothing left").isNull();
    }

    @Test
    @DisplayName("a live contract: paid counts only its live installments, as before")
    void liveContractUnchanged() {
        Installment deposit = installment(InstallmentMilestone.ON_SIGNING, 30_000_000);
        deposit.reach(AT, DAY);
        deposit.pay(BigDecimal.valueOf(30_000_000), AT);
        Installment rest = installment(InstallmentMilestone.AFTER_DELIVERY, 70_000_000);

        InstallmentStanding standing = InstallmentService.standingOf(order, List.of(deposit, rest));

        assertThat(standing.total()).isEqualByComparingTo("100000000");
        assertThat(standing.paid()).isEqualByComparingTo("30000000");
        assertThat(standing.paymentStatus()).isEqualTo(InstallmentStanding.ContractPaymentStatus.PARTIALLY_PAID);
    }
}

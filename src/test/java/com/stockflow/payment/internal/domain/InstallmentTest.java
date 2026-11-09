package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 4B-6, kltn-docs 15 §5.1: an installment's life - its milestone, the receipts that pay it, a void, a cancellation. */
class InstallmentTest {

    private static final Instant AT = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    private static Installment of(InstallmentMilestone milestone, long amount, Integer dueDays) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "HD-20261109-000001-1", "SO-20261109-000001", 2, "Second", milestone, null, null,
                BigDecimal.valueOf(amount), "VND", dueDays);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("opened NOT_DUE; its milestone makes it DUE, due its days later - once")
    void milestoneMakesItDue() {
        Installment installment = of(InstallmentMilestone.AFTER_DELIVERY, 1_000, 30);
        assertThat(installment.status()).isEqualTo(InstallmentStatus.NOT_DUE);
        assertThat(installment.reach(AT, DAY)).isTrue();
        assertThat(installment.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(installment.dueDate()).isEqualTo(DAY.plusDays(30));
        assertThat(installment.reach(AT.plusSeconds(86_400), DAY.plusDays(1))).as("a milestone comes once").isFalse();
        assertThat(installment.dueDate()).isEqualTo(DAY.plusDays(30));
    }

    @Test
    @DisplayName("paid before delivery: due at its milestone with no due day - it blocks the handover instead (BR-08)")
    void beforeDeliveryHasNoDueDay() {
        Installment installment = of(InstallmentMilestone.BEFORE_DELIVERY, 1_000, null);
        installment.reach(AT, DAY);
        assertThat(installment.status()).isEqualTo(InstallmentStatus.DUE);
        assertThat(installment.dueDate()).isNull();
    }

    @Test
    @DisplayName("paid early, it keeps PARTIALLY_PAID at its milestone and from then has a due day (§4.1 step 3)")
    void paidEarlyKeepsItsStatus() {
        Installment installment = of(InstallmentMilestone.AFTER_DELIVERY, 1_000, 10);
        installment.pay(BigDecimal.valueOf(400), AT);
        assertThat(installment.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(installment.dueDate()).isNull();
        installment.reach(AT, DAY);
        assertThat(installment.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(installment.dueDate()).isEqualTo(DAY.plusDays(10));
        assertThat(installment.outstanding()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("paid in full: PAID, with when; it then takes no more money")
    void paidInFull() {
        Installment installment = of(InstallmentMilestone.ON_SIGNING, 1_000, 0);
        installment.reach(AT, DAY);
        installment.pay(BigDecimal.valueOf(1_000), AT);
        assertThat(installment.status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(installment.paidAt()).isEqualTo(AT);
        assertThatThrownBy(() -> installment.pay(BigDecimal.ONE, AT)).extracting(InstallmentTest::code)
                .isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);
    }

    @Test
    @DisplayName("no line pays more than it still owes (BR-05: nothing left over)")
    void neverMoreThanOwed() {
        Installment installment = of(InstallmentMilestone.ON_SIGNING, 1_000, 0);
        installment.pay(BigDecimal.valueOf(700), AT);
        assertThatThrownBy(() -> installment.pay(BigDecimal.valueOf(301), AT)).extracting(InstallmentTest::code)
                .isEqualTo(ErrorCode.PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING);
        assertThat(installment.paidAmount()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("OVERDUE stays OVERDUE when paid in part, and is PAID when paid in full (§5.1)")
    void overdueStaysUntilPaidInFull() {
        Installment overdue = new Installment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING,
                null, null, BigDecimal.valueOf(1_000), BigDecimal.ZERO, "VND", 0, AT, DAY, InstallmentStatus.OVERDUE,
                null, null, 3L);
        overdue.pay(BigDecimal.valueOf(300), AT);
        assertThat(overdue.status()).isEqualTo(InstallmentStatus.OVERDUE);
        overdue.pay(BigDecimal.valueOf(700), AT);
        assertThat(overdue.status()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    @DisplayName("a receipt voided: the money no longer counts and the status says what is left")
    void unpayRecomputes() {
        Installment reached = of(InstallmentMilestone.ON_SIGNING, 1_000, 5);
        reached.reach(AT, DAY);
        reached.pay(BigDecimal.valueOf(1_000), AT);
        reached.unpay(BigDecimal.valueOf(400), DAY);
        assertThat(reached.status()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        assertThat(reached.paidAt()).isNull();
        reached.unpay(BigDecimal.valueOf(600), DAY);
        assertThat(reached.status()).isEqualTo(InstallmentStatus.DUE);

        Installment early = of(InstallmentMilestone.AFTER_DELIVERY, 1_000, 5);
        early.pay(BigDecimal.valueOf(1_000), AT);
        early.unpay(BigDecimal.valueOf(1_000), DAY);
        assertThat(early.status()).as("its milestone never came").isEqualTo(InstallmentStatus.NOT_DUE);

        Installment late = of(InstallmentMilestone.ON_SIGNING, 1_000, 0);
        late.reach(AT, DAY.minusDays(3));
        late.pay(BigDecimal.valueOf(1_000), AT);
        late.unpay(BigDecimal.valueOf(200), DAY);
        assertThat(late.status()).as("its due day is past").isEqualTo(InstallmentStatus.OVERDUE);
    }

    @Test
    @DisplayName("its order cancelled: not paid in full, it is CANCELLED and keeps what it was paid; a paid one stays")
    void cancel() {
        Installment partly = of(InstallmentMilestone.ON_SIGNING, 1_000, 0);
        partly.pay(BigDecimal.valueOf(250), AT);
        assertThat(partly.cancel(AT)).isTrue();
        assertThat(partly.status()).isEqualTo(InstallmentStatus.CANCELLED);
        assertThat(partly.paidAmount()).isEqualByComparingTo("250");
        assertThat(partly.outstanding()).isEqualByComparingTo("0");
        assertThat(partly.reach(AT, DAY)).as("a cancelled installment never falls due").isFalse();
        assertThatThrownBy(() -> partly.pay(BigDecimal.ONE, AT)).extracting(InstallmentTest::code)
                .isEqualTo(ErrorCode.INSTALLMENT_NOT_PAYABLE);

        Installment paid = of(InstallmentMilestone.ON_SIGNING, 1_000, 0);
        paid.pay(BigDecimal.valueOf(1_000), AT);
        assertThat(paid.cancel(AT)).isFalse();
        assertThat(paid.status()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    @DisplayName("the transfer reference: contract number and installment (15 §4.2 step 5)")
    void transferReference() {
        assertThat(of(InstallmentMilestone.ON_SIGNING, 1, 0).transferReference()).isEqualTo("HD-20261109-000001-1 DOT2");
    }
}

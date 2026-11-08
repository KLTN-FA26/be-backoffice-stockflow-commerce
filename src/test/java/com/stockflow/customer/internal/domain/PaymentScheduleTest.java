package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.PaymentMilestone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A customer's default payment schedule (4B-1; kltn-docs 18 BR-03, 14 BR-13). */
class PaymentScheduleTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    private static PaymentSchedule.Installment line(String percent, PaymentMilestone milestone, int days) {
        return new PaymentSchedule.Installment("Đợt", new BigDecimal(percent), milestone, days);
    }

    private static ErrorCode refused(Runnable action) {
        return ((BusinessException) catchThrowable(action::run)).errorCode();
    }

    @Test
    @DisplayName("shares above 0, two decimals at most, adding up to exactly 100; 1 to 12 instalments; 0-365 days")
    void shares() {
        PaymentSchedule ok = PaymentSchedule.create(CUSTOMER, List.of(line("33.33", PaymentMilestone.ON_SIGNING, 0),
                line("33.33", PaymentMilestone.BEFORE_DELIVERY, 0), line("33.34", PaymentMilestone.BEFORE_DELIVERY, 7)),
                null, SALES, false, false, NOW);
        assertThat(ok.installments()).extracting(PaymentSchedule.Installment::percent)
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(ok.approvedBy()).isNull();

        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, List.of(), null, SALES, false, false, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, List.of(line("99.99", PaymentMilestone.ON_SIGNING,
                0)), null, SALES, false, false, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, List.of(line("50.005", PaymentMilestone.ON_SIGNING,
                0), line("49.995", PaymentMilestone.ON_SIGNING, 0)), null, SALES, false, false, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, List.of(line("100", PaymentMilestone.ON_SIGNING,
                366)), null, SALES, false, false, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, List.of(line("0", PaymentMilestone.ON_SIGNING, 0),
                line("100", PaymentMilestone.ON_SIGNING, 0)), null, SALES, false, false, NOW)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, Collections.nCopies(13, line("1",
                PaymentMilestone.ON_SIGNING, 0)), null, SALES, false, false, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("after delivery: an approver's only, for approved credit, and recorded as their approval")
    void afterDelivery() {
        List<PaymentSchedule.Installment> credit = List.of(line("40", PaymentMilestone.ON_SIGNING, 0),
                line("60", PaymentMilestone.AFTER_DELIVERY, 30));
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, credit, null, SALES, false, true, NOW)))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, credit, null, SALES, true, false, NOW)))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);

        UUID accountant = UUID.randomUUID();
        PaymentSchedule approved = PaymentSchedule.create(CUSTOMER, credit, " note ", accountant, true, true, NOW);
        assertThat(approved.paysAfterDelivery()).isTrue();
        assertThat(approved.approvedBy()).isEqualTo(accountant);
        assertThat(approved.approvedAt()).isEqualTo(NOW);
        assertThat(approved.note()).isEqualTo("note");

        approved.replace(List.of(line("100", PaymentMilestone.ON_SIGNING, 0)), null, SALES, false, true, NOW);
        assertThat(approved.approvedBy()).isNull();
        assertThat(approved.setBy()).isEqualTo(SALES);
    }
}

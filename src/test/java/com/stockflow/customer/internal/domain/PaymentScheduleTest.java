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
    @DisplayName("shares above 0, two decimals at most, adding up to exactly 100; 1 to 12 installments; 0-365 days")
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
    @DisplayName("after delivery: only on approved credit; Sales' proposal awaits accounting, an accountant's is approved "
            + "as saved; a change of the installments clears the approval, a change of the note keeps it")
    void afterDelivery() {
        List<PaymentSchedule.Installment> credit = List.of(line("40", PaymentMilestone.ON_SIGNING, 0),
                line("60", PaymentMilestone.AFTER_DELIVERY, 30));
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, credit, null, SALES, false, false, NOW)))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThat(refused(() -> PaymentSchedule.create(CUSTOMER, credit, null, SALES, true, false, NOW)))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);

        PaymentSchedule proposed = PaymentSchedule.create(CUSTOMER, credit, " note ", SALES, false, true, NOW);
        assertThat(proposed.paysAfterDelivery()).isTrue();
        assertThat(proposed.awaitingApproval()).isTrue();
        assertThat(proposed.approvedBy()).isNull();
        assertThat(proposed.note()).isEqualTo("note");

        UUID accountant = UUID.randomUUID();
        Instant later = NOW.plusSeconds(60);
        assertThat(refused(() -> proposed.approve(accountant, false, later)))
                .isEqualTo(ErrorCode.PAYMENT_SCHEDULE_NEEDS_CREDIT);
        assertThat(proposed.approve(accountant, true, later)).isTrue();
        assertThat(proposed.approvedBy()).isEqualTo(accountant);
        assertThat(proposed.approvedAt()).isEqualTo(later);
        assertThat(proposed.approve(UUID.randomUUID(), true, NOW.plusSeconds(120))).isFalse();
        assertThat(proposed.approvedBy()).isEqualTo(accountant);

        // Sales rewrites the note only: still approved. Sales changes a share: it awaits accounting again.
        proposed.replace(credit, "another note", SALES, false, true, NOW.plusSeconds(180));
        assertThat(proposed.approvedBy()).isEqualTo(accountant);
        proposed.replace(List.of(line("50", PaymentMilestone.ON_SIGNING, 0),
                line("50", PaymentMilestone.AFTER_DELIVERY, 30)), null, SALES, false, true, NOW.plusSeconds(240));
        assertThat(proposed.awaitingApproval()).isTrue();

        // An accountant's own schedule is approved as saved; back to signing only, nothing is left to approve.
        proposed.replace(credit, null, accountant, true, true, NOW.plusSeconds(300));
        assertThat(proposed.approvedBy()).isEqualTo(accountant);
        assertThat(proposed.approvedAt()).isEqualTo(NOW.plusSeconds(300));
        proposed.replace(List.of(line("100", PaymentMilestone.ON_SIGNING, 0)), null, SALES, false, true, NOW);
        assertThat(proposed.approvedBy()).isNull();
        assertThat(proposed.awaitingApproval()).isFalse();
        assertThat(proposed.setBy()).isEqualTo(SALES);
        assertThat(refused(() -> proposed.approve(accountant, true, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}

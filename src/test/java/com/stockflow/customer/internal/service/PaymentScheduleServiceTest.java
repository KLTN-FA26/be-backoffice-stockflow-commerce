package com.stockflow.customer.internal.service;

import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.api.PaymentMilestone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A customer with no schedule pays everything on signing, and that one installment is named as the Vietnamese contract
 * drafted from it prints it (WF-02 re-check): "On signing" used to reach the customer's copy and the PDF.
 */
class PaymentScheduleServiceTest {

    @Test
    @DisplayName("no schedule set: one installment of 100% on signing, named in Vietnamese")
    void unsetIsAllOnSigningInVietnamese() {
        DefaultPaymentSchedule unset = PaymentScheduleService.unset(UUID.randomUUID());

        assertThat(unset.configured()).isFalse();
        assertThat(unset.installments()).singleElement().satisfies(only -> {
            assertThat(only.name()).isEqualTo("Thanh toán khi ký");
            assertThat(only.milestone()).isEqualTo(PaymentMilestone.ON_SIGNING);
            assertThat(only.percent()).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(only.dueDays()).isZero();
        });
    }
}

package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-427, kltn-docs 18 §3, 15 BR-12: a customer's credit that agrees with itself, no Spring. Since 4B-10 the credit
 * is a limit and the days to pay after delivery, both or neither: the allowed payment terms, the default term, the
 * deposit share and the deposit balance policy went with the money ledger - how a customer pays is the signed
 * contract's installment plan.
 */
class CreditProfileTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    private static ErrorCode refused(Runnable change) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(change::run);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("credit carries a limit and days to pay, with who approved it and why")
    void consistentCredit() {
        CreditProfile profile = CreditProfile.create(CUSTOMER, new BigDecimal("50000000"), 30, ACCOUNTANT, NOW,
                " approved by finance ");

        assertThat(profile.hasCredit()).isTrue();
        assertThat(profile.creditLimit()).isEqualByComparingTo("50000000");
        assertThat(profile.creditTermDays()).isEqualTo(30);
        assertThat(profile.currency()).isEqualTo("VND");
        assertThat(profile.approvedBy()).isEqualTo(ACCOUNTANT);
        assertThat(profile.approvedAt()).isEqualTo(NOW);
        assertThat(profile.note()).isEqualTo("approved by finance");

        CreditProfile none = CreditProfile.create(CUSTOMER, null, null, ACCOUNTANT, NOW, " ");
        assertThat(none.hasCredit()).as("neither: no credit").isFalse();
        assertThat(none.note()).isNull();
        assertThat(CreditProfile.create(CUSTOMER, BigDecimal.ZERO, 0, ACCOUNTANT, NOW, null).hasCredit())
                .as("a limit of zero is still a limit, of nothing").isTrue();
    }

    @Test
    @DisplayName("contradictions are CREDIT_PROFILE_INVALID")
    void contradictions() {
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, new BigDecimal("1000"), null, ACCOUNTANT, NOW, null)))
                .as("a limit without days to pay").isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, null, 30, ACCOUNTANT, NOW, null)))
                .as("days to pay without a limit").isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, new BigDecimal("-1"), 30, ACCOUNTANT, NOW, null)))
                .isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, new BigDecimal("1000"), 400, ACCOUNTANT, NOW, null)))
                .isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, new BigDecimal("1000"), -1, ACCOUNTANT, NOW, null)))
                .isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThatThrownBy(() -> CreditProfile.create(CUSTOMER, null, null, null, NOW, null))
                .as("always someone who approved it").isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a refused change leaves the credit as it was; an accepted one replaces it, withdrawal included")
    void changes() {
        CreditProfile profile = CreditProfile.create(CUSTOMER, new BigDecimal("1000"), 30, ACCOUNTANT, NOW, null);

        assertThatThrownBy(() -> profile.change(null, 30, ACCOUNTANT, NOW, null)).isInstanceOf(BusinessException.class);
        assertThat(profile.creditLimit()).isEqualByComparingTo("1000");
        assertThat(profile.creditTermDays()).isEqualTo(30);

        UUID approver = UUID.randomUUID();
        Instant later = NOW.plusSeconds(3600);
        profile.change(new BigDecimal("2000"), 45, approver, later, "raised");
        assertThat(profile.creditLimit()).isEqualByComparingTo("2000");
        assertThat(profile.creditTermDays()).isEqualTo(45);
        assertThat(profile.approvedBy()).isEqualTo(approver);
        assertThat(profile.approvedAt()).isEqualTo(later);

        profile.change(null, null, approver, later, "withdrawn");
        assertThat(profile.hasCredit()).isFalse();
        assertThat(profile.creditTermDays()).isNull();
    }
}

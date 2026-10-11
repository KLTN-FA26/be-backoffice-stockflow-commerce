package com.stockflow.customer.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-427, kltn-docs 18 §3: commercial terms that agree with themselves, no Spring. */
class CreditProfileTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    private static ErrorCode refused(Runnable change) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(change::run);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("credit terms carry a limit and days to pay; a deposit share is between 0 and 100")
    void consistentTerms() {
        CreditProfile profile = CreditProfile.create(CUSTOMER, true, true, true, CommercialTerm.CREDIT,
                new BigDecimal("30"), new BigDecimal("50000000"), 30, ACCOUNTANT, NOW, "approved by finance");

        assertThat(profile.allowCredit()).isTrue();
        assertThat(profile.creditLimit()).isEqualByComparingTo("50000000");
        assertThat(profile.approvedBy()).isEqualTo(ACCOUNTANT);
        assertThat(profile.note()).isEqualTo("approved by finance");
    }

    @Test
    @DisplayName("contradictions are CREDIT_PROFILE_INVALID")
    void contradictions() {
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, false, false, false, CommercialTerm.PREPAID, null,
                null, null, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, false, false, CommercialTerm.CREDIT, null,
                null, null, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, false, true, CommercialTerm.PREPAID, null,
                new BigDecimal("1000"), null, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, true, false, CommercialTerm.PREPAID,
                new BigDecimal("100"), null, null, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, false, false, CommercialTerm.PREPAID,
                new BigDecimal("30"), null, null, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, false, true, CommercialTerm.PREPAID, null,
                new BigDecimal("-1"), 30, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        assertThat(refused(() -> CreditProfile.create(CUSTOMER, true, false, true, CommercialTerm.PREPAID, null,
                new BigDecimal("1000"), 400, ACCOUNTANT, NOW, null))).isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
    }

    @Test
    @DisplayName("a refused change leaves the terms as they were")
    void refusedChangeKeepsTheTerms() {
        CreditProfile profile = CreditProfile.create(CUSTOMER, true, false, true, CommercialTerm.PREPAID, null,
                new BigDecimal("1000"), 30, ACCOUNTANT, NOW, null);

        assertThatThrownBy(() -> profile.change(true, false, true, CommercialTerm.PREPAID, null, null, 30, ACCOUNTANT,
                NOW, null)).isInstanceOf(BusinessException.class);
        assertThat(profile.creditLimit()).isEqualByComparingTo("1000");
    }
}

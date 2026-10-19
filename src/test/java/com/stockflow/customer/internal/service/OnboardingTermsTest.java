package com.stockflow.customer.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.internal.domain.CreditProfile;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision B1: at onboarding Sales may choose PREPAID or DEPOSIT with its share; CREDIT stays with the approver. */
class OnboardingTermsTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void nothingChosenRecordsNothingAndTheCustomerPrepays() {
        assertThat(CustomerOnboardingService.onboardingTerms(CUSTOMER, null, null, SALES, NOW)).isEmpty();
    }

    @Test
    void aDepositWithItsAgreedShareIsRecordedAsSetBySales() {
        CreditProfile profile = CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.DEPOSIT,
                new BigDecimal("30.5"), SALES, NOW).orElseThrow();
        assertThat(profile.customerId()).isEqualTo(CUSTOMER);
        assertThat(profile.allowPrepaid()).isTrue();
        assertThat(profile.allowDeposit()).isTrue();
        assertThat(profile.allowCredit()).as("never at onboarding").isFalse();
        assertThat(profile.defaultTerm()).isEqualTo(CommercialTerm.DEPOSIT);
        assertThat(profile.depositPercent()).isEqualByComparingTo("30.5");
        assertThat(profile.creditLimit()).isNull();
        assertThat(profile.approvedBy()).as("who set it").isEqualTo(SALES);
        assertThat(profile.approvedAt()).isEqualTo(NOW);
        assertThat(profile.note()).contains("onboarding");
    }

    @Test
    void prepaidIsRecordedWithoutAShare() {
        CreditProfile profile = CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.PREPAID, null, SALES,
                NOW).orElseThrow();
        assertThat(profile.defaultTerm()).isEqualTo(CommercialTerm.PREPAID);
        assertThat(profile.allowDeposit()).isFalse();
        assertThat(profile.allowCredit()).isFalse();
    }

    @Test
    void creditAndInconsistentSharesAreRefused() {
        assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.CREDIT, null,
                SALES, NOW)).extracting(OnboardingTermsTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.DEPOSIT, null,
                SALES, NOW)).extracting(OnboardingTermsTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.PREPAID,
                BigDecimal.TEN, SALES, NOW)).extracting(OnboardingTermsTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, null, BigDecimal.TEN, SALES, NOW))
                .extracting(OnboardingTermsTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.DEPOSIT,
                new BigDecimal("12.345"), SALES, NOW)).extracting(OnboardingTermsTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        for (String share : new String[]{"0", "100", "-5", "150"}) {
            assertThatThrownBy(() -> CustomerOnboardingService.onboardingTerms(CUSTOMER, CommercialTerm.DEPOSIT,
                    new BigDecimal(share), SALES, NOW)).as(share).extracting(OnboardingTermsTest::code)
                    .isEqualTo(ErrorCode.CREDIT_PROFILE_INVALID);
        }
    }
}

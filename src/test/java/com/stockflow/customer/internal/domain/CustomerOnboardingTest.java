package com.stockflow.customer.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tax code normalisation and the onboarding lifecycle of kltn-docs 18 §5 on the aggregate. */
class CustomerOnboardingTest {

    @Test
    void taxCodesNormaliseToTenDigitsOrTenDashThree() {
        assertThat(TaxCode.normalise(" 0312345678 ")).isEqualTo("0312345678");
        assertThat(TaxCode.normalise("0312 345 678")).isEqualTo("0312345678");
        assertThat(TaxCode.normalise("0312345678-001")).isEqualTo("0312345678-001");
        assertThat(TaxCode.normalise("0312345678001")).isEqualTo("0312345678-001");
        assertThat(TaxCode.normalise("0312.345.678-001")).isEqualTo("0312345678-001");
        // A household business: its representative's personal identification number (T1, Circular 86/2024 art. 5(5)).
        assertThat(TaxCode.normalise("079085012345")).isEqualTo("079085012345");
        assertThat(TaxCode.normalise("079 085 012 345")).isEqualTo("079085012345");
        for (String bad : new String[]{null, " ", "031234567", "03123456789", "0312345678-01", "ABC1234567",
                "0312345678-0012", "0312345678_001", "07908501234", "0790850123456-1", "079085012345-001"}) {
            assertThatThrownBy(() -> TaxCode.normalise(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onboardingStartsPendingWithANormalisedTaxCodeAndOnlyActivationMakesItActive() {
        Customer customer = pending();
        assertThat(customer.status()).isEqualTo(CustomerStatus.PENDING_ACTIVATION);
        assertThat(customer.taxCode()).isEqualTo("0312345678-001");

        assertThatThrownBy(() -> customer.changeStatus(CustomerStatus.ACTIVE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.changeStatus(CustomerStatus.BLOCKED)).isInstanceOf(IllegalStateException.class);
        customer.activatePortal();
        assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThatThrownBy(customer::activatePortal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.changeStatus(CustomerStatus.PENDING_ACTIVATION))
                .isInstanceOf(IllegalStateException.class);
        customer.changeStatus(CustomerStatus.BLOCKED);
        assertThatThrownBy(customer::activatePortal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.changeStatus(CustomerStatus.ACTIVE)).as("lifting a block needs a reason")
                .isInstanceOf(IllegalArgumentException.class);
        customer.changeStatus(CustomerStatus.ACTIVE, "Settled");
    }

    @Test
    void aPendingCustomerCanBeClosedButNotActivatedAndAGivenTaxCodeMustBeWellFormed() {
        Customer customer = pending();
        customer.changeStatus(CustomerStatus.INACTIVE);
        assertThatThrownBy(customer::activatePortal).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> customer.changeStatus(CustomerStatus.PENDING_ACTIVATION))
                .isInstanceOf(IllegalStateException.class);
        // Optional since 2026-10-12 (household businesses), but never malformed when given.
        assertThatThrownBy(() -> Customer.onboard(UUID.randomUUID(), UUID.randomUUID(), "Co", "a@example.test",
                null, "12345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Customer.onboard(UUID.randomUUID(), null, "Co", "a@example.test",
                null, "0312345678")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aLegacyCustomerWithoutTaxCodeStaysValidAndCanBeGivenOne() {
        Customer legacy = Customer.register(UUID.randomUUID(), UUID.randomUUID(), "Old Co", "old@example.test", null);
        assertThat(legacy.taxCode()).isNull();
        legacy.changeTaxCode("0109876543");
        assertThat(legacy.taxCode()).isEqualTo("0109876543");
        assertThatThrownBy(() -> legacy.changeTaxCode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aShopMayHaveNoTaxCodeAndAClosedCustomerReopensOnlyWithAReason() {
        Customer shop = Customer.onboard(UUID.randomUUID(), UUID.randomUUID(), "Quan nho", "shop@example.test",
                null, "  ");
        assertThat(shop.taxCode()).isNull();
        assertThat(shop.status()).isEqualTo(CustomerStatus.PENDING_ACTIVATION);
        shop.changeStatus(CustomerStatus.INACTIVE);
        assertThatThrownBy(() -> shop.changeStatus(CustomerStatus.ACTIVE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shop.changeStatus(CustomerStatus.BLOCKED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> shop.reopen(" ", false)).isInstanceOf(IllegalArgumentException.class);
        shop.reopen("came back", false);
        assertThat(shop.status()).isEqualTo(CustomerStatus.PENDING_ACTIVATION);
        assertThatThrownBy(() -> shop.reopen("again", false)).isInstanceOf(IllegalStateException.class);

        Customer activated = pending();
        activated.activatePortal();
        activated.changeStatus(CustomerStatus.INACTIVE);
        activated.reopen("came back", true);
        assertThat(activated.status()).isEqualTo(CustomerStatus.ACTIVE);

        assertThatThrownBy(() -> new CustomerStatusChange(UUID.randomUUID(), UUID.randomUUID(),
                CustomerStatus.INACTIVE, CustomerStatus.ACTIVE, " ", null, java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Customer pending() {
        return Customer.onboard(UUID.randomUUID(), UUID.randomUUID(), "Cong ty A", "Buyer@Example.test",
                "0901234567", "0312345678001");
    }
}

package com.stockflow.customer.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.stockflow.customer.internal.domain.BusinessCustomerProfile.PaymentTerm.CREDIT;
import static com.stockflow.customer.internal.domain.BusinessCustomerProfile.PaymentTerm.DEPOSIT;
import static com.stockflow.customer.internal.domain.BusinessCustomerProfile.PaymentTerm.PREPAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessCustomerProfileTest {
    @Test
    void salesMustSupplyCompanyTaxCodeAndAtLeastOneAllowedTerm() {
        assertThatThrownBy(() -> new BusinessCustomerProfile(" ", "0123456789", Set.of(PREPAID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCustomerProfile("Acme", null, Set.of(PREPAID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCustomerProfile("Acme", "0123456789", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void termsAreAnImmutableAllowListWithoutImplicitGrants() {
        var supplied = new HashSet<>(Set.of(DEPOSIT, PREPAID));
        var profile = new BusinessCustomerProfile(" Acme ", " 0123456789 ", supplied);
        supplied.add(CREDIT);
        assertThat(profile.companyName()).isEqualTo("Acme");
        assertThat(profile.taxCode()).isEqualTo("0123456789");
        assertThat(profile.paymentTerms()).containsExactlyInAnyOrder(DEPOSIT, PREPAID);
        assertThatThrownBy(() -> profile.paymentTerms().add(CREDIT))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

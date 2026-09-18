package com.stockflow.customer.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerTest {

    @Test
    void firstAddressOfEachTypeBecomesDefaultAndSelectingAnotherClearsTheOldOne() {
        Customer customer = customer();
        CustomerAddress first = customer.addAddress(address(AddressType.SHIPPING, false));
        CustomerAddress second = customer.addAddress(address(AddressType.SHIPPING, false));

        assertThat(customer.address(first.id()).orElseThrow().defaultAddress()).isTrue();
        assertThat(customer.address(second.id()).orElseThrow().defaultAddress()).isFalse();

        customer.setDefaultAddress(second.id());

        assertThat(customer.address(first.id()).orElseThrow().defaultAddress()).isFalse();
        assertThat(customer.address(second.id()).orElseThrow().defaultAddress()).isTrue();
    }

    @Test
    void deletingTheDefaultPromotesARemainingAddressOfTheSameType() {
        Customer customer = customer();
        CustomerAddress first = customer.addAddress(address(AddressType.BILLING, false));
        CustomerAddress second = customer.addAddress(address(AddressType.BILLING, false));

        customer.deleteAddress(first.id());

        assertThat(customer.address(second.id()).orElseThrow().defaultAddress()).isTrue();
    }

    @Test
    void addressRequiresThePostMergerProvinceAndWardLevels() {
        assertThatThrownBy(() -> new CustomerAddress(UUID.randomUUID(), AddressType.SHIPPING,
                "Minh", "0901234567", "12 Nguyen Hue", null, null, "Ben Nghe",
                "79", "Ho Chi Minh City", "VN", null, false, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wardCode");
    }

    @Test
    void addressNormalisesVietnamPhoneNumbersAndRejectsAnInvalidOne() {
        CustomerAddress address = new CustomerAddress(UUID.randomUUID(), AddressType.SHIPPING,
                "Minh", "0901 234 567", "12 Nguyen Hue", null, "26734", "Ben Nghe",
                "79", "Ho Chi Minh City", "VN", null, false, 0L);

        assertThat(address.phone()).isEqualTo("+84901234567");
        assertThatThrownBy(() -> new CustomerAddress(UUID.randomUUID(), AddressType.SHIPPING,
                "Minh", "not-a-phone", "12 Nguyen Hue", null, "26734", "Ben Nghe",
                "79", "Ho Chi Minh City", "VN", null, false, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a valid Vietnamese phone number");
    }

    @Test
    void reconstructionRejectsTwoDefaultsForOneType() {
        assertThatThrownBy(() -> new Customer(UUID.randomUUID(), UUID.randomUUID(), "Minh",
                "minh@example.com", "0901234567", null, CustomerStatus.ACTIVE,
                List.of(address(AddressType.SHIPPING, true), address(AddressType.SHIPPING, true)),
                0L, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one default");
    }

    private static Customer customer() {
        return Customer.register(UUID.randomUUID(), UUID.randomUUID(), "Minh",
                "Minh@Example.com", "0901234567");
    }

    private static CustomerAddress address(AddressType type, boolean defaultAddress) {
        return new CustomerAddress(UUID.randomUUID(), type, "Minh", "0901234567",
                "12 Nguyen Hue", null, "26734", "Ben Nghe", "79",
                "Ho Chi Minh City", "VN", null, defaultAddress, 0L);
    }
}

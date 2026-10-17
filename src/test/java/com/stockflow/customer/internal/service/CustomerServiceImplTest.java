package com.stockflow.customer.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.RegisterCustomerCommand;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.repository.CustomerSearchRepository;
import com.stockflow.identity.api.PortalAccountService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerServiceImplTest {

    @Test
    void selfRegistrationIsRefusedBeforeReadingOrCreatingAnyAccount() {
        CustomerRepository customers = mock(CustomerRepository.class);
        CustomerSearchRepository search = mock(CustomerSearchRepository.class);
        PortalAccountService identities = mock(PortalAccountService.class);
        var service = new CustomerServiceImpl(customers, search, identities,
                mock(com.stockflow.customer.internal.domain.CreditProfileRepository.class),
                mock(com.stockflow.customer.internal.domain.CustomerStatusHistory.class), java.time.Clock.systemUTC());

        assertThatThrownBy(() -> service.register(new RegisterCustomerCommand("Customer",
                "Customer@Example.com", "0901234567", "StrongPass1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CUSTOMER_REGISTRATION_DISABLED));
        verifyNoInteractions(customers, search, identities);
    }

    private static com.stockflow.customer.internal.domain.CustomerAddress address(
            com.stockflow.customer.internal.domain.AddressType type, String line1) {
        return new com.stockflow.customer.internal.domain.CustomerAddress(UUID.randomUUID(), type,
                "Nguyen Van A", "0901234567", line1, null, "26734", "Ben Nghe", "79", "Ho Chi Minh", "VN",
                null, false, 0L);
    }

    private CustomerServiceImpl serviceWith(Customer customer) {
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findById(customer.id())).thenReturn(Optional.of(customer));
        return new CustomerServiceImpl(customers, mock(CustomerSearchRepository.class), mock(PortalAccountService.class),
                mock(com.stockflow.customer.internal.domain.CreditProfileRepository.class),
                mock(com.stockflow.customer.internal.domain.CustomerStatusHistory.class), java.time.Clock.systemUTC());
    }

    @Test
    void checkoutWithoutAnyShippingAddressSaysSoInsteadOfAGenericValidationError() {
        var customer = Customer.register(UUID.randomUUID(), UUID.randomUUID(), "An", "an@example.com", null);
        var service = serviceWith(customer);

        assertThatThrownBy(() -> service.resolveCheckout(customer.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.SHIPPING_ADDRESS_REQUIRED));
    }

    @Test
    void checkoutRefusesAnAddressThatIsNotTheCustomersOrIsTheWrongKind() {
        var customer = Customer.register(UUID.randomUUID(), UUID.randomUUID(), "An", "an@example.com", null);
        var billing = customer.addAddress(address(com.stockflow.customer.internal.domain.AddressType.BILLING, "bill"));
        customer.addAddress(address(com.stockflow.customer.internal.domain.AddressType.SHIPPING, "ship"));
        var service = serviceWith(customer);

        assertThatThrownBy(() -> service.resolveCheckout(customer.id(), UUID.randomUUID(), null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.ADDRESS_NOT_FOUND));
        assertThatThrownBy(() -> service.resolveCheckout(customer.id(), billing.id(), null))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.VALIDATION_FAILED));
        assertThat(service.resolveCheckout(customer.id(), null, null).shippingAddress().line1()).isEqualTo("ship");
    }
}

package com.stockflow.customer.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.RegisterCustomerCommand;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.repository.CustomerSearchRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.RegisteredAccount;
import com.stockflow.identity.api.TokenResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerServiceImplTest {

    @Test
    void registrationCreatesIdentityThenLinkedCustomerAndReturnsFirstToken() {
        CustomerRepository customers = mock(CustomerRepository.class);
        IdentityService identities = mock(IdentityService.class);
        UUID userId = UUID.randomUUID();
        when(customers.findByEmail("customer@example.com")).thenReturn(Optional.empty());
        when(identities.registerCustomer(any())).thenReturn(new RegisteredAccount(userId,
                new TokenResponse("jwt", "Bearer", 3600)));
        when(customers.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new CustomerServiceImpl(customers, mock(CustomerSearchRepository.class), identities);

        var result = service.register(new RegisterCustomerCommand("Customer",
                " Customer@Example.COM ", "0901234567", "StrongPass1"));

        assertThat(result.customer().userId()).isEqualTo(userId);
        assertThat(result.customer().email()).isEqualTo("customer@example.com");
        assertThat(result.accessToken()).isEqualTo("jwt");
        verify(customers).save(any(Customer.class));
    }

    @Test
    void registrationRejectsAnExistingCustomerEmailBeforeCreatingAnotherIdentity() {
        CustomerRepository customers = mock(CustomerRepository.class);
        IdentityService identities = mock(IdentityService.class);
        when(customers.findByEmail("customer@example.com"))
                .thenReturn(Optional.of(Customer.register(UUID.randomUUID(), UUID.randomUUID(),
                        "Customer", "customer@example.com", "0901234567")));
        var service = new CustomerServiceImpl(customers, mock(CustomerSearchRepository.class), identities);

        assertThatThrownBy(() -> service.register(new RegisterCustomerCommand("Customer",
                "Customer@Example.com", "0901234567", "StrongPass1")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
        verifyNoInteractions(identities);
    }
}

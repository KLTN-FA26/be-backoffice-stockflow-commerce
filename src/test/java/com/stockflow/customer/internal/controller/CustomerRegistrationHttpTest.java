package com.stockflow.customer.internal.controller;

import com.stockflow.customer.api.CustomerRegistration;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerRegistrationHttpTest {

    @Test
    void validRegistrationReturnsCustomerAndFirstToken() throws Exception {
        CustomerService customers = mock(CustomerService.class);
        UUID customerId = UUID.randomUUID();
        when(customers.register(any())).thenReturn(new CustomerRegistration(
                new CustomerSummary(customerId, UUID.randomUUID(), "Minh", "minh@example.com",
                        "0901234567", null, "ACTIVE", 0L, null), "jwt", "Bearer", 3600));
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerRegistrationController(customers)).build();

        mvc.perform(post("/api/v1/customers/registrations").contentType("application/json")
                        .content("""
                                {"fullName":"Minh","email":"minh@example.com","phone":"0901234567",
                                 "password":"StrongPass1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.accessToken").value("jwt"));
    }

    @Test
    void weakPasswordIsRejectedBeforeCallingTheService() throws Exception {
        CustomerService customers = mock(CustomerService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerRegistrationController(customers)).build();

        mvc.perform(post("/api/v1/customers/registrations").contentType("application/json")
                        .content("""
                                {"fullName":"Minh","email":"minh@example.com","phone":"0901234567",
                                 "password":"weak"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(customers);
    }
}

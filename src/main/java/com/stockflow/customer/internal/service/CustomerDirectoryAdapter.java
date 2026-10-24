package com.stockflow.customer.internal.service;

import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The customer module's side of {@link CustomerDirectory}: the address a customer message goes to, read when it is
 * sent. Notification declares the interface because it cannot depend on this module (customer already depends on
 * notification); see the interface for the whole argument.
 *
 * <p>Every customer, whatever its status: the messages are about orders the customer placed - a cancellation, a
 * refund owed - and a blocked or closed customer still needs to hear about those. The customer's e-mail is the one it
 * signed up or was invited with (it cannot change, {@code Customer.email} is final), which is also what each of its
 * orders froze as the contact at checkout.</p>
 */
@Component
class CustomerDirectoryAdapter implements CustomerDirectory {

    private final CustomerRepository customers;

    CustomerDirectoryAdapter(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerContact> contact(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return customers.findById(customerId)
                .map(customer -> new CustomerContact(customer.id(), customer.fullName(), customer.email()));
    }
}

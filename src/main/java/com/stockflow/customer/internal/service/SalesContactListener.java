package com.stockflow.customer.internal.service;

import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.SalesContacts;
import com.stockflow.identity.api.IdentityService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keeps who sent each customer a quote or a contract, and when (sales-owner plan §10.5 step 3): what the managers' list
 * of change requests reads to warn that the salesperson a customer asks for was in touch with them lately. The order
 * module publishes the facts; customer cannot ask order (order depends on customer), so it keeps its own copy.
 *
 * <p>After the sender's commit, in a transaction of its own, retried from the event publication registry. Idempotent:
 * the stored time only moves forward. An event naming a customer or an account that does not exist is ignored rather
 * than left failing in the registry.</p>
 */
@Component
public class SalesContactListener {

    private final SalesContacts contacts;
    private final CustomerRepository customers;
    private final IdentityService identity;

    SalesContactListener(SalesContacts contacts, CustomerRepository customers, IdentityService identity) {
        this.contacts = contacts;
        this.customers = customers;
        this.identity = identity;
    }

    @ApplicationModuleListener
    public void on(QuoteStatusChanged changed) {
        if ("SENT".equals(changed.toStatus())) {
            record(changed.customerId(), changed.changedBy(), SalesContacts.Source.QUOTE, changed.occurredAt());
        }
    }

    @ApplicationModuleListener
    public void on(SalesContractStatusChanged changed) {
        if ("SENT".equals(changed.status())) {
            record(changed.customerId(), changed.changedBy(), SalesContacts.Source.CONTRACT, changed.occurredAt());
        }
    }

    private void record(UUID customerId, UUID salesUserId, SalesContacts.Source source, Instant at) {
        if (customerId == null || salesUserId == null || at == null || !customers.existsById(customerId)
                || identity.userLabels(List.of(salesUserId)).isEmpty()) {
            return;
        }
        contacts.record(customerId, salesUserId, source, at);
    }
}

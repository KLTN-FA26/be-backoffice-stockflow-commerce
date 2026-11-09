package com.stockflow.customer.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerLegalProfile;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerChange;
import com.stockflow.customer.internal.domain.CustomerChangeLog;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.entity.CustomerLegalProfileJpaEntity;
import com.stockflow.customer.internal.repository.CustomerLegalProfileJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's legal profile (kltn-docs 18 §3, BR-05, §6; QD-14; team plan 4B-4): Sales records the registered business
 * address, the legal representative and their title before a contract is drawn up for the customer. Every change is
 * kept in the customer's change history, as a change of its company name is.
 */
@Service
@Transactional
public class CustomerLegalProfileService implements CustomerLegalProfiles {

    /** The profile as Sales enters it; {@code expectedVersion} null only for a customer that has none yet. */
    public record Save(UUID customerId, String registeredAddress, String representativeName,
                       String representativeTitle, Long expectedVersion, UUID by) { }

    private final CustomerRepository customers;
    private final CustomerLegalProfileJpaRepository profiles;
    private final CustomerChangeLog changes;
    private final Clock clock;

    CustomerLegalProfileService(CustomerRepository customers, CustomerLegalProfileJpaRepository profiles,
                                CustomerChangeLog changes, Clock clock) {
        this.customers = customers;
        this.profiles = profiles;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerLegalProfile> legalProfile(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return profiles.findByCustomerId(customerId).map(row -> view(customer, row));
    }

    /**
     * Records the profile, whole. The customer row is locked first, so two saves of one profile are decided one after
     * the other and the second, holding a stale version, is refused.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code VALIDATION_FAILED} for a blank field;
     *                           {@code OPTIMISTIC_LOCK} when the profile changed since it was read
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "customer-legal-profile", resourceId = "#command.customerId()")
    public CustomerLegalProfile save(Save command) {
        Customer customer = customers.findForUpdate(command.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        String address = required(command.registeredAddress(), "registeredAddress", 500);
        String representative = required(command.representativeName(), "representativeName", 200);
        String title = required(command.representativeTitle(), "representativeTitle", 100);
        Optional<CustomerLegalProfileJpaEntity> existing = profiles.findByCustomerId(command.customerId());
        if (existing.isPresent() ? command.expectedVersion() == null
                || existing.get().getVersion() != command.expectedVersion()
                : command.expectedVersion() != null && command.expectedVersion() != -1L) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "The legal profile of customer %s changed since it was read".formatted(command.customerId()));
        }
        CustomerLegalProfileJpaEntity row = existing.orElseGet(() ->
                new CustomerLegalProfileJpaEntity(Identifiers.newId(), command.customerId()));
        Instant now = clock.instant();
        record(customer.id(), CustomerChange.Field.REGISTERED_ADDRESS, row.getRegisteredAddress(), address,
                command.by(), now);
        record(customer.id(), CustomerChange.Field.REPRESENTATIVE, row.getRepresentativeName(), representative,
                command.by(), now);
        record(customer.id(), CustomerChange.Field.REPRESENTATIVE_TITLE, row.getRepresentativeTitle(), title,
                command.by(), now);
        row.apply(address, representative, title);
        return view(customer, profiles.saveAndFlush(row));
    }

    private void record(UUID customerId, CustomerChange.Field field, String before, String after, UUID by,
                        Instant at) {
        if (!Objects.equals(before, after)) {
            changes.record(new CustomerChange(Identifiers.newId(), customerId, field, before, after, by, at));
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + ": required, at most " + max
                    + " characters");
        }
        return value.strip();
    }

    private static CustomerLegalProfile view(Customer customer, CustomerLegalProfileJpaEntity row) {
        return new CustomerLegalProfile(customer.id(), customer.fullName(), customer.taxCode(),
                row.getRegisteredAddress(), row.getRepresentativeName(), row.getRepresentativeTitle(),
                row.getVersion());
    }
}

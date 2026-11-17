package com.stockflow.customer.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Customers' legal profiles (kltn-docs 18 §3, BR-05; team plan 4B-4), read by the sales contract. Read only here: Sales
 * records them through the customer module's own endpoint.
 */
public interface CustomerLegalProfiles {

    /**
     * The customer's legal profile; empty while its registered address and legal representative are not recorded - a
     * contract is not drawn up for it then (18 BR-05).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}
     */
    Optional<CustomerLegalProfile> legalProfile(UUID customerId);
}

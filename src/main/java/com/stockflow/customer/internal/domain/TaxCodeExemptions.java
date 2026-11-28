package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for {@code customer.customer_tax_code_exemption}: a Sales Manager let a business customer be created without a
 * tax code (sales-owner plan BR-S14, D4), and why.
 */
public interface TaxCodeExemptions {

    /** Records the exemption of a customer just created; joins the caller's transaction. */
    void grant(UUID customerId, String reason, UUID grantedBy, Instant at);
}

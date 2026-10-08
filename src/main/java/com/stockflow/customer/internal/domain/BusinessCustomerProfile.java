package com.stockflow.customer.internal.domain;

import java.util.Objects;
import java.util.Set;

/**
 * The business details Sales supplies in addition to Customer's existing contact fields.
 * Persistence is pending the DB owner's B2B schema; this value is deliberately not a JPA mapping.
 * Selecting CREDIT permits that payment term; it does not grant a credit limit or release an order.
 */
public record BusinessCustomerProfile(String companyName, String taxCode, Set<PaymentTerm> paymentTerms) {

    public enum PaymentTerm { DEPOSIT, CREDIT, PREPAID }

    public BusinessCustomerProfile {
        companyName = required(companyName, "companyName");
        taxCode = required(taxCode, "taxCode");
        paymentTerms = Set.copyOf(Objects.requireNonNull(paymentTerms, "paymentTerms"));
        if (paymentTerms.isEmpty()) throw new IllegalArgumentException("At least one payment term is required");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }
}

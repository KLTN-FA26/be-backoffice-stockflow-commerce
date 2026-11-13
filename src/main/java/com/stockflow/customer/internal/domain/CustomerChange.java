package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of who the customer is on paper - its company name, its tax code, its legal profile (4B-4, 18 §6;
 * its invoice e-mail and bank account since WF-05 E1) - with
 * the value before and after, who made it and when (decision 2026-10-17: the customer's contact may rename the company,
 * and every change is kept).
 */
public record CustomerChange(UUID id, UUID customerId, Field field, String oldValue, String newValue, UUID changedBy,
                             Instant changedAt) {

    /** What changed. */
    public enum Field {
        FULL_NAME, TAX_CODE, REGISTERED_ADDRESS, REPRESENTATIVE, REPRESENTATIVE_TITLE,
        /** WF-05 E1: where the customer's e-invoices are sent. */
        INVOICE_EMAIL,
        /** WF-05 E1: the bank account, on one line - number, bank, holder. */
        BANK_ACCOUNT
    }

    public CustomerChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(changedAt, "changedAt");
        if (Objects.equals(oldValue, newValue)) throw new IllegalArgumentException("A change changes the value");
    }
}

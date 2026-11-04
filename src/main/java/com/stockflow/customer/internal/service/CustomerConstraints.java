package com.stockflow.customer.internal.service;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;

/**
 * Names the unique index a lost race tripped, so the loser gets the 409 that says why instead of a
 * generic duplicate. The service checks first; these indexes decide only between concurrent writers.
 */
final class CustomerConstraints {

    /** V20261017000830: one customer per tax code in every status (was uk_customer_tax_code_live, live ones only). */
    static final String TAX_CODE = "uk_customer_tax_code";

    private CustomerConstraints() {
    }

    static boolean isTaxCodeTaken(Throwable error) {
        return violated(error, TAX_CODE);
    }

    /** True when the cause chain names {@code constraint}, by Hibernate's parse or the driver's message. */
    static boolean violated(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraint)) return true;
        }
        return false;
    }
}

package com.stockflow.payment.internal.controller;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerSearch;

import java.util.List;
import java.util.UUID;

/**
 * The customers a back-office search box means, read through {@code customer :: api}: the accountant holds no customer
 * permission, so the back office cannot pick one from the customer list (FE BE-16), and the payment schema is not
 * JOINed to the customer's (ADR-0007). The installments list and the receipts list ask the same way, so a term finds
 * the same customers on both.
 */
final class CustomerSearchTerms {

    /**
     * At most this many customers a search may match: their ids go into one {@code IN} list. A search broader than that
     * is refused rather than cut short, which would drop some customers' rows without a word.
     */
    static final int SEARCH_MAX_CUSTOMERS = 1_000;

    private CustomerSearchTerms() {
    }

    /**
     * The customers whose name or tax code contains {@code term}, case and accents aside. Null for no search; empty when
     * it matched nobody.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} when it matches more than {@link #SEARCH_MAX_CUSTOMERS}
     */
    static List<UUID> idsMatching(CustomerSearch customers, String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        List<UUID> matching = customers.idsMatching(term, SEARCH_MAX_CUSTOMERS + 1);
        if (matching.size() > SEARCH_MAX_CUSTOMERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The search matches more than %d customers"
                    .formatted(SEARCH_MAX_CUSTOMERS), List.of(FieldError.localized("search",
                    "matches too many customers", "TooBroad", "refusal.payment.searchTooBroad",
                    SEARCH_MAX_CUSTOMERS))).localized("refusal.payment.searchTooBroad", SEARCH_MAX_CUSTOMERS);
        }
        return matching;
    }
}

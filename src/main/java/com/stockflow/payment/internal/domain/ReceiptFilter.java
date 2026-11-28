package com.stockflow.payment.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Which payment receipts a list, its totals and its export are about (4B-6; 2026-11-14 plan, BE2-A): the same filter
 * for all three, so the sum the accountant reads above the list is the sum of the rows they export.
 *
 * @param reference    the statement reference, compared as BR-03 compares it ({@link PaymentReceipt#normalize}); blank
 *                     is none
 * @param receivedFrom the first day the money was received, inclusive - the bank statement's day the accountant typed
 *                     ({@code received_on}), not the day the receipt was recorded
 * @param receivedTo   the last such day, inclusive
 * @param search       the accountant's search box (audit bo-24); blank is none. A receipt matches when its customer is
 *                     one of {@code searchCustomers} or one of its lines paid an installment whose contract number or
 *                     order number contains the term, case aside. {@code reference} stays the exact comparison above.
 * @param searchCustomers the customers whose name or tax code the term matches, read through {@code customer :: api}
 *                     before (this schema is not JOINed, ADR-0007); null or empty when it matched nobody, which leaves
 *                     the numbers to match. Ignored without a term.
 * @throws BusinessException {@code VALIDATION_FAILED} when {@code receivedFrom} is after {@code receivedTo}
 */
public record ReceiptFilter(UUID customerId, PaymentReceiptStatus status, String reference, LocalDate receivedFrom,
                            LocalDate receivedTo, String search, List<UUID> searchCustomers) {

    public ReceiptFilter {
        reference = reference == null || reference.isBlank() ? null : reference.strip();
        search = search == null || search.isBlank() ? null : search.strip();
        searchCustomers = search == null || searchCustomers == null ? List.of() : List.copyOf(searchCustomers);
        if (receivedFrom != null && receivedTo != null && receivedFrom.isAfter(receivedTo)) {
            // The message and the field's both in the reader's language: the field error's code alone would be read
            // through error.RangeInverted, which no bundle has, and fall back to this English.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid date range: 'receivedFrom' must not be "
                    + "after 'receivedTo'", List.of(FieldError.localized("receivedFrom",
                    "must not be after 'receivedTo'", "RangeInverted", "refusal.payment.receivedRange")))
                    .localized("refusal.payment.receivedRange");
        }
    }

    /** Without the accountant's search box. */
    public ReceiptFilter(UUID customerId, PaymentReceiptStatus status, String reference, LocalDate receivedFrom,
                         LocalDate receivedTo) {
        this(customerId, status, reference, receivedFrom, receivedTo, null, null);
    }

    /** Every receipt. */
    public static ReceiptFilter none() {
        return new ReceiptFilter(null, null, null, null, null, null, null);
    }

    /** The reference as the receipts store it for comparison ({@code reference_normalized}), or null for none. */
    public String normalizedReference() {
        return reference == null ? null : PaymentReceipt.normalize(reference);
    }

    /**
     * The search term as a case-insensitive "contains" pattern for {@code LIKE ... ESCAPE '\'} (the escape
     * {@code Specs.contains} uses): lowered, its own {@code %}, {@code _} and backslash escaped so a typed one stays
     * literal - a {@code 50%} would otherwise match everything; null without a search.
     */
    public String searchPattern() {
        if (search == null) {
            return null;
        }
        String lowered = search.toLowerCase(java.util.Locale.ROOT);
        return "%" + lowered.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}

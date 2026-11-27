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
 * @throws BusinessException {@code VALIDATION_FAILED} when {@code receivedFrom} is after {@code receivedTo}
 */
public record ReceiptFilter(UUID customerId, PaymentReceiptStatus status, String reference, LocalDate receivedFrom,
                            LocalDate receivedTo) {

    public ReceiptFilter {
        reference = reference == null || reference.isBlank() ? null : reference.strip();
        if (receivedFrom != null && receivedTo != null && receivedFrom.isAfter(receivedTo)) {
            // The message and the field's both in the reader's language: the field error's code alone would be read
            // through error.RangeInverted, which no bundle has, and fall back to this English.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid date range: 'receivedFrom' must not be "
                    + "after 'receivedTo'", List.of(FieldError.localized("receivedFrom",
                    "must not be after 'receivedTo'", "RangeInverted", "refusal.payment.receivedRange")))
                    .localized("refusal.payment.receivedRange");
        }
    }

    /** Every receipt. */
    public static ReceiptFilter none() {
        return new ReceiptFilter(null, null, null, null, null);
    }

    /** The reference as the receipts store it for comparison ({@code reference_normalized}), or null for none. */
    public String normalizedReference() {
        return reference == null ? null : PaymentReceipt.normalize(reference);
    }
}

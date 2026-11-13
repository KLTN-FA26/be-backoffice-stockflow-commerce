package com.stockflow.design.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;

/**
 * The rules of a customer's quote request on a design (kltn-docs 12 §4.8, 16 §4.2 step 5, B40). Each is stated in the
 * table too ({@code design_quote_request}). One request per design is open at a time: asking again while it is open
 * changes what it asks - it is the one the sales staff answer, and it follows a returned design (B40).
 */
public final class QuoteRequestRules {

    /** The most cartons one request asks: no order is near it; it keeps a slip of the keyboard out of the sales list. */
    public static final int MAX_CARTONS = 1_000_000;

    private QuoteRequestRules() {
    }

    /**
     * What a customer asks: a whole number of cartons, at least one - a suggestion the quote need not follow (14
     * BR-22) - and, if any, a delivery date not before {@code today} in Vietnam.
     *
     * @param field where the request sits in the body ({@code ""}, or {@code "quoteRequest."} in a submission), so a
     *              refusal names the field as the client sent it
     */
    public static void requireValid(int cartons, LocalDate desiredDeliveryDate, LocalDate today, String field) {
        if (cartons < 1 || cartons > MAX_CARTONS) {
            throw invalid(field + "cartons", "cartons must be from 1 to " + MAX_CARTONS, "Range");
        }
        if (desiredDeliveryDate != null && desiredDeliveryDate.isBefore(today)) {
            throw invalid(field + "desiredDeliveryDate", "desiredDeliveryDate must be today or later",
                    "FutureOrPresent");
        }
    }

    /** Asked on its own, a quote is asked on an approved design; a draft asks with its submission (§4.8). */
    public static void requireApproved(DesignStatus status) {
        if (status != DesignStatus.APPROVED) {
            throw new BusinessException(ErrorCode.DESIGN_NOT_APPROVED,
                    "This design is " + status + "; ask for a quote on an approved design, or with a submission");
        }
    }

    private static BusinessException invalid(String field, String message, String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message, code)));
    }
}

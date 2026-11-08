package com.stockflow.design.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The rules of a customer's quote request on a design (kltn-docs 12 §4.8, 16 §4.2 step 5, B40). Each is stated in the
 * table too ({@code design_quote_request}).
 */
public final class QuoteRequestRules {

    private QuoteRequestRules() {
    }

    /**
     * What a customer asks: a whole number of cartons, at least one - a suggestion the quote need not follow (14
     * BR-22) - and, if any, a delivery date not before {@code today} in Vietnam.
     */
    public static void requireValid(int cartons, LocalDate desiredDeliveryDate, LocalDate today) {
        if (cartons < 1) {
            throw invalid("cartons", "cartons must be at least 1", "Min");
        }
        if (desiredDeliveryDate != null && desiredDeliveryDate.isBefore(today)) {
            throw invalid("desiredDeliveryDate", "desiredDeliveryDate must be today or later", "FutureOrPresent");
        }
    }

    /** Asked on its own, a quote is asked on an approved design; a draft asks with its submission (§4.8). */
    public static void requireApproved(DesignStatus status) {
        if (status != DesignStatus.APPROVED) {
            throw new BusinessException(ErrorCode.DESIGN_NOT_APPROVED,
                    "This design is " + status + "; ask for a quote on an approved design, or with a submission");
        }
    }

    /**
     * One open request per design (uk_design_quote_request_open): the one already open is what the sales staff answer,
     * and it follows a returned design through its next submission (B40).
     *
     * @param open the design's open request, or null
     */
    public static void requireNoneOpen(UUID open) {
        if (open != null) {
            throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUEST_OPEN,
                    "This design already has an open quote request", List.of(), Map.of("requestId", open.toString()));
        }
    }

    private static BusinessException invalid(String field, String message, String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message, code)));
    }
}

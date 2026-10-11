package com.stockflow.customer.internal.controller.dto;

import com.stockflow.customer.api.CommercialTerm;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Set a customer's commercial terms (kltn-docs 18 §3). The consistency rules — a deposit share
 * exactly when deposits are allowed, a limit and days exactly when credit is — are checked by the
 * domain and answered 400 {@code CREDIT_PROFILE_INVALID}.
 */
@Schema(description = "A customer's commercial terms")
public record SaveCreditProfileRequest(
        boolean allowPrepaid,
        boolean allowDeposit,
        boolean allowCredit,
        @Schema(example = "PREPAID") CommercialTerm defaultPaymentTerm,
        @Schema(example = "30", description = "Required when allowDeposit; above 0 and below 100") BigDecimal depositPercent,
        @Schema(example = "50000000", description = "Required when allowCredit; 0 or more") BigDecimal creditLimit,
        @Schema(example = "30", description = "Days to pay after delivery; required when allowCredit, 0-365")
        Integer creditTermDays,
        @Size(max = 1000, message = "note is at most 1000 characters") String note,
        @Schema(description = "The version read; omit when the customer has no terms yet") Long expectedVersion
) {
}

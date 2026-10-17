package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sales onboarding a business customer (kltn-docs 18 §4.1). The tax code is optional - household
 * businesses and small shops may supply it later - and normalised by the service when given (10 digits,
 * or 10-3 for a dependent unit, or 12 digits for a household business).
 *
 * <p>{@code paymentTerm} (decision B1, 2026-10-13): Sales may choose PREPAID or DEPOSIT, with the agreed
 * {@code depositPercent} for DEPOSIT (above 0, below 100, two decimals). CREDIT and its limit stay with the
 * credit approver ({@code PUT /customers/{id}/credit-profile}, 18 BR-02). Omitted: no terms are recorded and the
 * customer may only prepay, as before.</p>
 */
public record CreateBusinessCustomerRequest(
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 20) String taxCode,
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone,
        com.stockflow.customer.api.CommercialTerm paymentTerm,
        @jakarta.validation.constraints.Digits(integer = 3, fraction = 2,
                message = "depositPercent has at most two decimals") java.math.BigDecimal depositPercent
) {
    /** Without payment terms: the customer may only prepay until the approver sets terms. */
    public CreateBusinessCustomerRequest(String companyName, String taxCode, String contactName, String email,
                                         String phone) {
        this(companyName, taxCode, contactName, email, phone, null, null);
    }
}

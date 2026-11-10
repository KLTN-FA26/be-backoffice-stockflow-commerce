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
 * <p>No payment terms (4B-10): how the customer pays is each contract's installment plan; credit is set by the credit
 * approver ({@code PUT /customers/{id}/credit-profile}, 18 BR-02).</p>
 */
public record CreateBusinessCustomerRequest(
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 20) String taxCode,
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone
) {
}

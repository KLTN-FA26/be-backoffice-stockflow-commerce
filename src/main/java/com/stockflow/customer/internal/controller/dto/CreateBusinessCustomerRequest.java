package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sales onboarding a business customer (kltn-docs 18 §4.1). The tax code is required and normalised by
 * the service (10 digits, or 10-3 for a dependent unit). Payment terms are not here: only a credit
 * approver sets them, through {@code PUT /customers/{id}/credit-profile} (BR-02); until then the
 * customer may only prepay.
 */
public record CreateBusinessCustomerRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 20) String taxCode,
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone
) {
}

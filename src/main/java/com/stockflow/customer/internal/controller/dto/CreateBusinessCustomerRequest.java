package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sales onboarding a business customer. The tax code (kltn-docs 18 BR-01) is not here because the
 * customer schema has no column for it yet; payment terms are not here because only a credit
 * approver sets them ({@code PUT /customers/{id}/credit-profile}, BR-02).
 */
public record CreateBusinessCustomerRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone
) {
}

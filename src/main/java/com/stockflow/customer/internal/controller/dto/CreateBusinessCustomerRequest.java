package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Sales onboarding a business customer (kltn-docs 18 §4.1). The tax code is required (sales-owner plan BR-S14, D4,
 * reversing the decision of 2026-10-12): a Sales Manager may create one without it, with
 * {@code taxCodeExemption: true} and a reason (a household business without a code yet). Normalised by the service
 * (10 digits, or 10-3 for a dependent unit, or 12 digits for a household business).
 *
 * <p>No payment terms (4B-10): how the customer pays is each contract's installment plan; credit is set by the credit
 * approver ({@code PUT /customers/{id}/credit-profile}, 18 BR-02).</p>
 */
public record CreateBusinessCustomerRequest(
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 20) String taxCode,
        @NotBlank @Size(max = 200) String contactName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone,
        /*
         * Sales-owner plan BR-S02: who looks after the customer. Omitted: the creating salesperson, or nobody when a
         * Sales Manager creates it. Only a manager may name someone else.
         */
        UUID salesOwnerId,
        /* BR-S14 / D4: a manager creates the customer without a tax code; sent only without one, with the reason. */
        Boolean taxCodeExemption,
        @Size(max = 500) String taxCodeExemptionReason,
        /* Plan §10.7: the lead this customer is made from; its salesperson becomes the owner (LEAD_CONVERTED). */
        UUID leadId
) {

    /** Without an owner named: the default of BR-S02. */
    public CreateBusinessCustomerRequest(String companyName, String taxCode, String contactName, String email,
                                         String phone) {
        this(companyName, taxCode, contactName, email, phone, null, null, null, null);
    }

    public boolean exempted() {
        return Boolean.TRUE.equals(taxCodeExemption);
    }
}

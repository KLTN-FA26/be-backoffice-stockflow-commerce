package com.stockflow.customer.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The signed-in customer's own legal profile, read-only (R5-10; kltn-docs 18 §3, BR-12): what their contracts and
 * e-invoices name them by, so they can see it is right before signing. Flat, and without the version a change sends
 * back: the customer does not change it, Sales and the accountant do. A field not recorded is null.
 */
@Schema(name = "OwnLegalProfile")
public record OwnLegalProfileResponse(
        @Schema(description = "The registered company name") String legalName,
        String taxCode,
        String registeredAddress,
        String representativeName,
        String representativeTitle,
        @Schema(description = "Where e-invoices are sent") String invoiceEmail,
        String bankAccountNumber,
        String bankName,
        String bankAccountHolder) {
}

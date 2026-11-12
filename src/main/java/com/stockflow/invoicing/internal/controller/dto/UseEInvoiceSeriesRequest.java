package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The series invoices of a year are numbered under at a provider (TT 91 Điều 4, Phụ lục I), as registered with the tax
 * authority and created on the provider's portal.
 *
 * @param templateCode         one digit; {@code 1} is a VAT invoice
 * @param symbol               six characters, e.g. {@code C26TAA}; its two digits are the year's
 * @param providerTemplateCode the provider's own code of the template (SInvoice: {@code 1/001}); none for the sandbox
 */
public record UseEInvoiceSeriesRequest(
        @NotNull @Pattern(regexp = "[1-9]") String templateCode,
        @NotNull @Pattern(regexp = "[CK][0-9]{2}[TDLMNBGHXF][A-Z]{2}") String symbol,
        @NotNull @Min(2026) @Max(2099) Integer year,
        @Pattern(regexp = "[0-9A-Za-z/]{1,20}") String providerTemplateCode
) {
}

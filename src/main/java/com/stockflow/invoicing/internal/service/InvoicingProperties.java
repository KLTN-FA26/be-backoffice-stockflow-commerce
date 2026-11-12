package com.stockflow.invoicing.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * The invoicing module's settings that are a deployment's, not the accountant's ({@code stockflow.invoicing.*}). Which
 * provider issues and whether drafts are issued automatically are data ({@code invoicing.einvoice_provider_config}),
 * changed at runtime.
 *
 * @param dueWarningLead how long before an invoice's deadline the accountant is told; a day by default, which is the
 *                       start of the last day it can be issued on
 * @param stuckAfter     how long an invoice may wait for the provider's answer before the accountants are told it is
 *                       stuck; far longer than a provider takes, far shorter than the working day the law leaves
 * @param sandbox        how the sandbox provider behaves
 */
@ConfigurationProperties(prefix = "stockflow.invoicing")
public record InvoicingProperties(@DefaultValue("P1D") Duration dueWarningLead,
                                  @DefaultValue("PT30M") Duration stuckAfter, @DefaultValue Sandbox sandbox) {

    /**
     * @param processingDelay  how long the sandbox takes to "sign and get the tax authority's code": a submission is
     *                         accepted at once and its result is there only after this, as with a real provider
     * @param rejectedTaxCodes buyer tax codes the sandbox refuses as wrong, to rehearse a rejection
     */
    public record Sandbox(@DefaultValue("PT5S") Duration processingDelay,
                          @DefaultValue("0000000001") List<String> rejectedTaxCodes) {

        public Sandbox {
            rejectedTaxCodes = rejectedTaxCodes == null ? List.of() : List.copyOf(rejectedTaxCodes);
        }
    }
}

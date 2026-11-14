package com.stockflow.invoicing.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param corrections    what is done about an issued invoice that was wrong, where the law read leaves it open
 */
@ConfigurationProperties(prefix = "stockflow.invoicing")
public record InvoicingProperties(@DefaultValue("P1D") Duration dueWarningLead,
                                  @DefaultValue("PT30M") Duration stuckAfter, @DefaultValue Sandbox sandbox,
                                  @DefaultValue Corrections corrections) {

    /**
     * The constructor properties are bound through. Named, because a record with more than one constructor is
     * otherwise not bound through any: the binder would look for a no-argument one and fail at startup.
     */
    @ConstructorBinding
    public InvoicingProperties {
        stuckAfter = stuckAfter == null ? DEFAULT_STUCK_AFTER : stuckAfter;
        corrections = corrections == null ? new Corrections(false) : corrections;
    }

    /** As before corrections existed: nothing the law leaves open is turned on. */
    public InvoicingProperties(Duration dueWarningLead, Duration stuckAfter, Sandbox sandbox) {
        this(dueWarningLead, stuckAfter, sandbox, new Corrections(false));
    }

    /** As before stuck invoices were announced: the default wait, and nothing the law leaves open turned on. */
    public InvoicingProperties(Duration dueWarningLead, Sandbox sandbox) {
        this(dueWarningLead, DEFAULT_STUCK_AFTER, sandbox, new Corrections(false));
    }

    private static final Duration DEFAULT_STUCK_AFTER = Duration.ofMinutes(30);

    /**
     * @param errorNoticeWithCorrection whether a 04/SS-HĐĐT notice may also be recorded for an invoice that was
     *                                  adjusted or replaced. TT 91 Điều 10 khoản 1 điểm b does not ask for one, and
     *                                  whether a provider sends it by itself is not verified (plans/E0-legal.md row
     *                                  2, open item 5): off until the provider or the tax authority says it is wanted
     */
    public record Corrections(@DefaultValue("false") boolean errorNoticeWithCorrection) {
    }

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

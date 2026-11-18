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
 * @param withdrawAfter  how long after it was last sent an invoice the provider has no record of may be taken back
 *                       from it by the accountant (HĐ-1, {@code EInvoiceIssuance.withdraw}): longer than the provider
 *                       can still be working on a request it took in - SePay retries one for up to two hours
 * @param quotaWarningBelow the provider's remaining invoices under which the accountants are warned, once a day
 *                       (kltn-docs 21 BR-09); 0 turns the warning off
 * @param autoResendFor  how long after an invoice was submitted the reconciliation may still send it again by itself
 *                       while the provider has no record of it (HĐ-1). Past it the invoice is only asked about: its
 *                       last send then ages, and the accountant can take it back {@code withdrawAfter} later. Sent
 *                       again every quiet period for ever, its last send never aged and nothing could take it back -
 *                       while it held back every later-dated invoice of its series ({@code EINVOICE_OLDER_DRAFT_PENDING})
 * @param withdrawUnknownAfter how long after it was last sent an invoice the provider "cannot say" anything about
 *                       ({@code UNKNOWN}) may be taken back by the accountant, who checked with the provider first.
 *                       At least {@code withdrawAfter}; a day by default, far beyond SePay's documented retries, short
 *                       of the issuing deadline the series behind it would otherwise miss
 */
@ConfigurationProperties(prefix = "stockflow.invoicing")
public record InvoicingProperties(@DefaultValue("P1D") Duration dueWarningLead,
                                  @DefaultValue("PT30M") Duration stuckAfter, @DefaultValue Sandbox sandbox,
                                  @DefaultValue Corrections corrections, @DefaultValue("PT3H") Duration withdrawAfter,
                                  @DefaultValue("50") long quotaWarningBelow,
                                  @DefaultValue("PT1H") Duration autoResendFor,
                                  @DefaultValue("P1D") Duration withdrawUnknownAfter) {

    /**
     * The constructor properties are bound through. Named, because a record with more than one constructor is
     * otherwise not bound through any: the binder would look for a no-argument one and fail at startup.
     */
    @ConstructorBinding
    public InvoicingProperties {
        stuckAfter = stuckAfter == null ? DEFAULT_STUCK_AFTER : stuckAfter;
        corrections = corrections == null ? new Corrections(false) : corrections;
        withdrawAfter = withdrawAfter == null ? DEFAULT_WITHDRAW_AFTER : withdrawAfter;
        if (withdrawAfter.compareTo(Duration.ofHours(2)) <= 0) {
            // SePay retries a request it took in for up to two hours: taking an invoice back sooner could race it.
            throw new IllegalArgumentException("stockflow.invoicing.withdraw-after must be longer than two hours");
        }
        if (quotaWarningBelow < 0) {
            throw new IllegalArgumentException("stockflow.invoicing.quota-warning-below is not negative");
        }
        autoResendFor = autoResendFor == null ? DEFAULT_AUTO_RESEND_FOR : autoResendFor;
        if (autoResendFor.isZero() || autoResendFor.isNegative()) {
            throw new IllegalArgumentException("stockflow.invoicing.auto-resend-for must be positive");
        }
        withdrawUnknownAfter = withdrawUnknownAfter == null ? DEFAULT_WITHDRAW_UNKNOWN_AFTER : withdrawUnknownAfter;
        if (withdrawUnknownAfter.isZero() || withdrawUnknownAfter.isNegative()
                || withdrawUnknownAfter.compareTo(withdrawAfter) < 0) {
            throw new IllegalArgumentException(
                    "stockflow.invoicing.withdraw-unknown-after must be positive and at least withdraw-after");
        }
    }

    /** As before an unanswered provider could be withdrawn from: its default. */
    public InvoicingProperties(Duration dueWarningLead, Duration stuckAfter, Sandbox sandbox, Corrections corrections,
                               Duration withdrawAfter, long quotaWarningBelow, Duration autoResendFor) {
        this(dueWarningLead, stuckAfter, sandbox, corrections, withdrawAfter, quotaWarningBelow, autoResendFor,
                DEFAULT_WITHDRAW_UNKNOWN_AFTER);
    }

    /** As before automatic resending was bounded: its default. */
    public InvoicingProperties(Duration dueWarningLead, Duration stuckAfter, Sandbox sandbox, Corrections corrections,
                               Duration withdrawAfter, long quotaWarningBelow) {
        this(dueWarningLead, stuckAfter, sandbox, corrections, withdrawAfter, quotaWarningBelow,
                DEFAULT_AUTO_RESEND_FOR);
    }

    /** As before stuck invoices could be taken back and the quota was watched: their defaults. */
    public InvoicingProperties(Duration dueWarningLead, Duration stuckAfter, Sandbox sandbox, Corrections corrections) {
        this(dueWarningLead, stuckAfter, sandbox, corrections, DEFAULT_WITHDRAW_AFTER, DEFAULT_QUOTA_WARNING_BELOW);
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
    private static final Duration DEFAULT_WITHDRAW_AFTER = Duration.ofHours(3);
    private static final long DEFAULT_QUOTA_WARNING_BELOW = 50;
    private static final Duration DEFAULT_AUTO_RESEND_FOR = Duration.ofHours(1);
    private static final Duration DEFAULT_WITHDRAW_UNKNOWN_AFTER = Duration.ofDays(1);

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

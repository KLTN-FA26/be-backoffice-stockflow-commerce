package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The accountant issues a draft (WF-05 F-16): the version read, the warnings read and accepted, and why an original goes
 * out after the day of its delivery.
 *
 * @param acknowledgedWarnings warnings the accountant read on the draft and accepts (e.g. {@code TAX_CLASS_UNKNOWN},
 *                             {@code BUYER_PROFILE_CHANGED}); one found just before issuing that is not among them
 *                             refuses the issue with 409 {@code EINVOICE_DRAFT_OUT_OF_DATE}, {@code details.warnings}
 * @param lateReason           why an original is issued after its delivery day (decision Q-10, kept in the audit log);
 *                             required then - 400 {@code EINVOICE_LATE_ISSUE_REASON_REQUIRED} - ignored otherwise
 */
public record IssueEInvoiceRequest(@NotNull Long version,
                                   @Size(max = 20) List<@Size(max = 64) String> acknowledgedWarnings,
                                   @Size(max = 500) String lateReason) {
}

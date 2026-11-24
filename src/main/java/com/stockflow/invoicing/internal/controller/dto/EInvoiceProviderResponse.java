package com.stockflow.invoicing.internal.controller.dto;

/**
 * An e-invoice provider as configured. The account's password is never returned - only whether one is stored
 * (kltn-docs 21 BR-10).
 *
 * <p>What the provider can do (WF-05 F-11, BE-58), read from its adapter, so the back office offers only what works:
 * {@code accountInDeployment} - its account is the deployment's environment, not a stored row (SePay: no form);
 * {@code deploymentAccountReady} - that account is configured; {@code issuesCorrections} - it issues adjustments and
 * replacements; {@code adjustsReplacements} - it adjusts a replacement (none does: a replacement is replaced again, and
 * a return or failed delivery on it is reduced on the provider's portal, Q-9); {@code sendsErrorNotices} - it sends a
 * Mẫu 04/SS-HĐĐT notice (none does here: it is filed outside the system).</p>
 *
 * @param environment {@code SANDBOX} or {@code PRODUCTION}
 * @param active      whether invoices are issued through it now
 */
public record EInvoiceProviderResponse(String provider, String environment, String username, boolean secretSet,
                                       boolean active, boolean autoIssue, long version, boolean accountInDeployment,
                                       boolean deploymentAccountReady, boolean issuesCorrections,
                                       boolean adjustsReplacements, boolean sendsErrorNotices) {
}

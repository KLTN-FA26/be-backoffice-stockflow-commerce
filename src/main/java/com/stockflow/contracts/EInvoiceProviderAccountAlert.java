package com.stockflow.contracts;

import java.time.Instant;

/**
 * Published by the <b>invoicing</b> module when the account it issues e-invoices with needs somebody at the provider's
 * portal (HĐ-1; kltn-docs 21 BR-09): the plan's invoices are running out or used up, the plan is unpaid, or the
 * registration is not complete or not approved by the tax authority. Every invoice sent meanwhile is refused for the same
 * cause, so the accountants are told of the cause, once a day at most, rather than once per invoice - the refused
 * invoices themselves are listed as REJECTED with the cause's code and are issued again once it is fixed.
 *
 * <p>Codes and numbers only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing
 * this contract.</p>
 *
 * @param provider       the provider's code ({@code SEPAY_EINVOICE} ...)
 * @param problem        what is wrong
 * @param rejectionCode  the code the refused invoices carry; null for {@link Problem#QUOTA_LOW}
 * @param quotaRemaining the invoices left in the plan, for {@link Problem#QUOTA_LOW}; null otherwise
 * @param quotaThreshold the configured level under which it is announced, for {@link Problem#QUOTA_LOW}; null otherwise
 * @param detectedAt     when it was found
 */
public record EInvoiceProviderAccountAlert(String provider, Problem problem, String rejectionCode, Long quotaRemaining,
                                           Long quotaThreshold, Instant detectedAt) {

    public enum Problem { QUOTA_LOW, QUOTA_EXHAUSTED, BILLING_UNPAID, ACCOUNT_NOT_READY }
}

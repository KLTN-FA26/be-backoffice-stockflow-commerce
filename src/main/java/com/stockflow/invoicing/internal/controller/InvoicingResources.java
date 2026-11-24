package com.stockflow.invoicing.internal.controller;

/** Permission resource codes owned by the invoicing module (V20261120000300). */
final class InvoicingResources {

    /**
     * E-invoices: READ for the accountant, the Sales Manager and the administrator; CREATE (draw a draft up from a
     * delivered shipment), UPDATE (correct, void or reopen a draft), APPROVE (issue, and ask the provider again) and
     * EXPORT (the period register) for the accountant. READ also for the customer, for its own issued invoices on
     * the portal (V20261120000600): the back-office handlers refuse a portal account.
     */
    static final String EINVOICES = "einvoices";
    /**
     * The customer module's legal profiles (V20261120000100), guarding "send the invoice e-mail again" (audit r2 M-1):
     * its holders - Sales, the Sales Manager, the accountant, the administrator - are the ones told to record a missing
     * invoice e-mail and send the invoice on (HĐ-8; {@code EInvoiceIssuedNotificationListener}), so who is told and who
     * may act are one set. Sales staff hold no {@code einvoices} permission at all.
     */
    static final String LEGAL_PROFILES = "customer-legal-profiles";
    /** Which provider issues and whether complete drafts are issued automatically: the administrator's. */
    static final String SETTINGS = "einvoice-settings";

    private InvoicingResources() {
    }
}

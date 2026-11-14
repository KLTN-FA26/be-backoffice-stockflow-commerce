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
    /** Which provider issues and whether complete drafts are issued automatically: the administrator's. */
    static final String SETTINGS = "einvoice-settings";

    private InvoicingResources() {
    }
}

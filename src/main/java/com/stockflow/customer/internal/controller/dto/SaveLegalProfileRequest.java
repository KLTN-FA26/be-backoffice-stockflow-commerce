package com.stockflow.customer.internal.controller.dto;

/**
 * A customer's legal profile, whole (4B-4; WF-05 E1); {@code version} is the one read, absent for the first. Not
 * validated here: the service checks every field after the caller is known to be staff.
 *
 * @param invoiceEmail where e-invoices are sent; absent takes the customer's contact e-mail address
 * @param bankAccount  the customer's bank account for refunds; absent records none
 */
public record SaveLegalProfileRequest(
        String registeredAddress,
        String representativeName,
        String representativeTitle,
        String invoiceEmail,
        BankAccount bankAccount,
        Long version
) {

    /** All three are required when an account is given. */
    public record BankAccount(String accountNumber, String bankName, String accountHolder) {
    }
}

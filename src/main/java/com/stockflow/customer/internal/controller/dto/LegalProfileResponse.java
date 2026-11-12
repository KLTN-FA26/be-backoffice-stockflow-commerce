package com.stockflow.customer.internal.controller.dto;

import java.util.UUID;

/**
 * A customer's legal profile as staff read it (kltn-docs 18 §3, BR-12; WF-05 E1): what a contract and an e-invoice
 * name the buyer by. {@code version} goes back with a change.
 */
public record LegalProfileResponse(UUID customerId, String companyName, String taxCode, String registeredAddress,
                                   String representativeName, String representativeTitle, String invoiceEmail,
                                   BankAccount bankAccount, long version) {

    public record BankAccount(String accountNumber, String bankName, String accountHolder) {
    }
}

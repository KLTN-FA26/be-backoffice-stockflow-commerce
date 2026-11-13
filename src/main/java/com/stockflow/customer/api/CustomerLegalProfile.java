package com.stockflow.customer.api;

import java.util.UUID;

/**
 * Who a customer is on a contract and on an invoice (kltn-docs 18 §3 "thông tin pháp lý", BR-05, BR-12; QD-14; team
 * plan 4B-4; WF-05 E1): the company's name and tax code from its profile, the registered business address, legal
 * representative and their title that a sales contract names the buyer by, the e-mail address its e-invoices are sent
 * to, and its bank account. A contract and an invoice copy what they need when drawn up, so a later change leaves
 * documents already drawn up as they were (18 §6, 21 BR-06).
 *
 * @param taxCode      normalised; null for a household business that has none yet - an invoice is not issued then
 * @param invoiceEmail where the customer's e-invoices are sent (18 BR-12); null only on a profile recorded before it
 *                     was kept, for a customer whose contact address could not stand in
 * @param bankAccount  the customer's bank account, used to pay a refund back (18 §3); null when none is recorded
 * @param version      the version to send back when changing it
 */
public record CustomerLegalProfile(UUID customerId, String companyName, String taxCode, String registeredAddress,
                                   String representativeName, String representativeTitle, String invoiceEmail,
                                   BankAccount bankAccount, long version) {

    /** A bank account as a transfer names it: its number, the bank, and the name the account is held in. */
    public record BankAccount(String accountNumber, String bankName, String accountHolder) {
    }
}

package com.stockflow.payment.api;

/**
 * The company's own bank account a wholesale customer pays by transfer (kltn-docs 15 §1, §4.1 step 1: "hiển thị thông
 * tin tài khoản"), as configured for this deployment ({@code stockflow.payment.company-bank}). Not a secret - it is
 * shown to every customer with an order to pay - but never guessed: a deployment without it has none.
 *
 * @param bankName      the bank, as the customer's banking app names it
 * @param accountNumber the number to transfer to
 * @param accountName   the account holder, as the bank prints it
 */
public record CompanyBankAccount(String bankName, String accountNumber, String accountName) {
}

package com.stockflow.payment.api;

/**
 * The company's own bank account a wholesale customer pays by transfer (kltn-docs 15 §1, §4.1 step 1: "hiển thị thông
 * tin tài khoản"), as configured for this deployment ({@code stockflow.payment.company-bank}). Not a secret - it is
 * shown to every customer with an order to pay - but never guessed: a deployment without it has none.
 *
 * @param bankName      the bank, as the customer's banking app names it
 * @param accountNumber the number to transfer to
 * @param accountName   the account holder, as the bank prints it
 * @param bankBin       the bank's six-digit NAPAS BIN, which a VietQR code names the bank by (kltn-docs 16 BR-06: the
 *                      chat's transfer card carries one); null when it is not configured - the account is then shown
 *                      without a QR code
 */
public record CompanyBankAccount(String bankName, String accountNumber, String accountName, String bankBin) {

    /** An account without a BIN: shown, but no VietQR code is drawn for it. */
    public CompanyBankAccount(String bankName, String accountNumber, String accountName) {
        this(bankName, accountNumber, accountName, null);
    }
}

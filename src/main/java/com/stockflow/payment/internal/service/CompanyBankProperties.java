package com.stockflow.payment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The company's bank account for customer transfers ({@code stockflow.payment.company-bank}; environment
 * {@code COMPANY_BANK_NAME}, {@code COMPANY_BANK_ACCOUNT_NUMBER}, {@code COMPANY_BANK_ACCOUNT_NAME}). Blank by default:
 * only the local and dev profiles carry an obviously-demo account, so production cannot show a customer a made-up one.
 *
 * @param bankName      the bank
 * @param accountNumber the account number
 * @param accountName   the account holder
 */
@ConfigurationProperties(prefix = "stockflow.payment.company-bank")
public record CompanyBankProperties(String bankName, String accountNumber, String accountName) {

    /** Whether every part is set. */
    boolean configured() {
        return filled(bankName) && filled(accountNumber) && filled(accountName);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}

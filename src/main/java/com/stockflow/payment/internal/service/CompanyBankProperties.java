package com.stockflow.payment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The company's bank account for customer transfers ({@code stockflow.payment.company-bank}; environment
 * {@code COMPANY_BANK_NAME}, {@code COMPANY_BANK_ACCOUNT_NUMBER}, {@code COMPANY_BANK_ACCOUNT_NAME}). Blank by default:
 * only the local and dev profiles carry an obviously-demo account, so production cannot show a customer a made-up one.
 *
 * @param bankName      the bank
 * @param accountNumber the account number
 * @param accountName   the account holder
 * @param bankBin       the bank's NAPAS BIN ({@code COMPANY_BANK_BIN}), for the VietQR code of the chat's transfer card
 */
@ConfigurationProperties(prefix = "stockflow.payment.company-bank")
public record CompanyBankProperties(String bankName, String accountNumber, String accountName, String bankBin) {

    @ConstructorBinding
    public CompanyBankProperties {
    }

    /** Without a BIN ({@code COMPANY_BANK_BIN}): the account is shown, with no VietQR code. */
    public CompanyBankProperties(String bankName, String accountNumber, String accountName) {
        this(bankName, accountNumber, accountName, null);
    }

    /** The BIN when it is six digits, as NAPAS numbers banks; null otherwise. */
    String bin() {
        return bankBin != null && bankBin.strip().matches("\\d{6}") ? bankBin.strip() : null;
    }

    /** Whether every part is set. */
    boolean configured() {
        return filled(bankName) && filled(accountNumber) && filled(accountName);
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}

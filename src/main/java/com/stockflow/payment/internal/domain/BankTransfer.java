package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * One incoming transfer as the bank feed reports it, read from SePay's webhook or its transaction API - before it is
 * kept ({@link BankTransaction#received}).
 *
 * @param providerTransactionId SePay's id: the webhook's number, or {@code v2:<uuid>} from the transaction API, whose
 *                              ids are not the webhook's
 * @param accountNumber         the company account the money went to; null when SePay did not say
 * @param amount                whole dong, above zero
 * @param content               what the payer wrote; null when empty
 * @param bankReference         the bank's own reference of the transfer; null when the bank gave none
 * @param paymentCode           what SePay's payment-code recognition read out of the content; null when nothing
 * @param transactionAt         when the bank booked it
 * @param raw                   what SePay sent, as JSON, kept with the row
 */
public record BankTransfer(String providerTransactionId, String accountNumber, BigDecimal amount, String content,
                           String bankReference, String paymentCode, Instant transactionAt, String raw) {

    public static final int CONTENT_MAX = 500;
    public static final int REFERENCE_MAX = 100;

    public BankTransfer {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(transactionAt, "transactionAt");
        Objects.requireNonNull(raw, "raw");
        if (providerTransactionId.isBlank() || providerTransactionId.length() > 64) {
            throw new IllegalArgumentException("A provider's transaction id is 1 to 64 characters");
        }
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A transfer is a whole number of dong above zero");
        }
        accountNumber = blankToNull(accountNumber, 32);
        content = blankToNull(content, CONTENT_MAX);
        bankReference = blankToNull(bankReference, REFERENCE_MAX);
        paymentCode = blankToNull(paymentCode, 64);
    }

    /**
     * What tells this transfer apart from every other one, whichever way SePay reported it: the bank's reference, upper
     * case without spaces (as 15 BR-03 compares references); without one, a digest of the account, the amount, the time
     * and the content - the webhook's id and the transaction API's id are not the same for one transfer.
     */
    public String dedupeKey() {
        if (bankReference != null) {
            return "REF:" + PaymentReceipt.normalize(bankReference);
        }
        String facts = String.join("|", accountNumber == null ? "" : accountNumber,
                amount.stripTrailingZeros().toPlainString(), String.valueOf(transactionAt.getEpochSecond()),
                content == null ? "" : content.strip().toUpperCase(Locale.ROOT));
        try {
            return "SHA256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String blankToNull(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String kept = value.strip();
        return kept.length() > max ? kept.substring(0, max) : kept;
    }
}

package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/**
 * The payload of a VietQR code (NAPAS's profile of EMVCo Merchant-Presented QR) for a transfer to the company's account
 * (kltn-docs 16 §4.4 step 9, BR-06): the client draws the QR from this string, and any Vietnamese banking app that scans
 * it fills in the bank, the account, the amount and the transfer content - the customer types none of them, which is
 * the point of BR-06.
 *
 * <pre>
 * 00 02 "01"                 payload format
 * 01 02 "12"                 dynamic: it carries an amount
 * 38 ..                      merchant account (NAPAS)
 *    00 10 "A000000727"      NAPAS's GUID
 *    01 ..                   beneficiary: 00 = the bank's BIN, 01 = the account number
 *    02 08 "QRIBFTTA"        service: transfer to an account
 * 53 03 "704"                VND
 * 54 ..                      amount, whole dong
 * 58 02 "VN"
 * 62 ..  08 ..               the transfer content
 * 63 04 CRC16-CCITT (0xFFFF) over everything before it, "6304" included
 * </pre>
 *
 * <p>Here, in the payment module's api, since R5-9: the chat's transfer card (kltn-docs 16 §4.4) and the transfer
 * instructions of the order page ({@code GET /installments/me/{id}/transfer-instructions}, and the staff's
 * {@code GET /installments/{id}/transfer-instructions}) draw the same code from {@link #forInstallment}, so the two can
 * never disagree about what a customer scans. It moved from {@code chat}, which uses it through {@code payment :: api};
 * the payload it builds did not change.</p>
 */
public final class VietQr {

    private static final String VND = "VND";

    private static final String NAPAS_GUID = "A000000727";
    private static final String TRANSFER_TO_ACCOUNT = "QRIBFTTA";
    /** What banking apps show of the content reliably: plain ASCII, at most this long. */
    private static final int MAX_CONTENT = 50;

    private VietQr() {
    }

    /**
     * @param bin     the bank's six-digit NAPAS BIN
     * @param account the account number
     * @param amount  whole dong, more than zero; null for a code without an amount
     * @param content what the transfer says (the contract and installment code); diacritics are removed
     */
    public static String payload(String bin, String account, BigDecimal amount, String content) {
        Objects.requireNonNull(bin, "bin");
        Objects.requireNonNull(account, "account");
        if (!bin.matches("\\d{6}")) {
            throw new IllegalArgumentException("A NAPAS BIN is six digits");
        }
        String accountDigits = account.replaceAll("\\s", "");
        if (!accountDigits.matches("[0-9A-Za-z]{1,19}")) {
            throw new IllegalArgumentException("A bank account number is 1 to 19 letters and digits");
        }
        StringBuilder qr = new StringBuilder();
        qr.append(field("00", "01"));
        qr.append(field("01", amount == null ? "11" : "12"));
        String beneficiary = field("00", bin) + field("01", accountDigits);
        qr.append(field("38", field("00", NAPAS_GUID) + field("01", beneficiary) + field("02", TRANSFER_TO_ACCOUNT)));
        qr.append(field("53", "704"));
        if (amount != null) {
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("A transfer is of more than nothing");
            }
            qr.append(field("54", amount.setScale(0, java.math.RoundingMode.UP).toPlainString()));
        }
        qr.append(field("58", "VN"));
        String text = ascii(content);
        if (!text.isEmpty()) {
            qr.append(field("62", field("08", text)));
        }
        qr.append("6304");
        return qr + crc16(qr.toString());
    }

    /**
     * The code for paying what is left of one installment into the company's account, or null when there is nothing to
     * scan: no BIN configured for the account, nothing left to pay, not VND, or an installment PAID or CANCELLED - a
     * code left on a cancelled order's card would let the customer pay an order that no longer exists.
     *
     * @param status    the installment's status, as the payment module names it
     * @param reference what the transfer says: the contract's number and the installment's
     */
    public static String forInstallment(CompanyBankAccount bank, BigDecimal outstanding, String currency,
                                        String status, String reference) {
        boolean payable = outstanding != null && outstanding.signum() > 0 && VND.equals(currency)
                && !"PAID".equals(status) && !"CANCELLED".equals(status);
        return bank == null || bank.bankBin() == null || !payable ? null
                : payload(bank.bankBin(), bank.accountNumber(), outstanding, reference);
    }

    /** Plain ASCII letters, digits, spaces and {@code -._/}: what every banking app keeps of a transfer's content. */
    static String ascii(String content) {
        if (content == null) {
            return "";
        }
        String plain = Normalizer.normalize(content.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9 \\-._/]", " ").replaceAll("\\s+", " ").strip();
        return plain.length() > MAX_CONTENT ? plain.substring(0, MAX_CONTENT).strip() : plain;
    }

    private static String field(String id, String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > 99) {
            throw new IllegalArgumentException("An EMV QR field holds at most 99 bytes");
        }
        return id + String.format("%02d", length) + value;
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial 0xFFFF, as EMVCo specifies for field 63. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}

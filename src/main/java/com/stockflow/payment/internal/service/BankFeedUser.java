package com.stockflow.payment.internal.service;

import java.util.UUID;

/**
 * The bank feed's system user (V20270103000100): who {@code payment_receipt.recorded_by} names for a receipt recorded
 * automatically from a bank transfer. DISABLED, with no password and no role: it signs in nowhere, and a receipt it
 * recorded reads "Bank feed (SePay)" wherever the recorder's name is shown.
 */
final class BankFeedUser {

    static final UUID ID = UUID.fromString("0193c000-0000-7000-8000-00000000bf01");

    private BankFeedUser() {
    }
}

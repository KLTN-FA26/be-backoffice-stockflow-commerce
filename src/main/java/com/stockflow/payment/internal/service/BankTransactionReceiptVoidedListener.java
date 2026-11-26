package com.stockflow.payment.internal.service;

import com.stockflow.contracts.PaymentReceiptVoided;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A voided receipt recorded from a bank transfer puts the transfer back in the Accountant's queue (part C): the money
 * came in all the same, and is assigned again or ignored. After the void's commit, in a transaction of its own - the
 * void does not wait for the queue, and a receipt the Accountant recorded by hand names no transfer, so nothing happens.
 */
@Component
class BankTransactionReceiptVoidedListener {

    private final BankTransactionService transactions;

    BankTransactionReceiptVoidedListener(BankTransactionService transactions) {
        this.transactions = transactions;
    }

    @ApplicationModuleListener
    public void on(PaymentReceiptVoided event) {
        transactions.receiptVoided(event.receiptId(), event.reference());
    }
}

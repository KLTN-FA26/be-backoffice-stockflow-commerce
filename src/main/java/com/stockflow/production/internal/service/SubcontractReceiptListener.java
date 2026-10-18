package com.stockflow.production.internal.service;

import com.stockflow.contracts.GoodsReceiptConfirmed;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A goods receipt reaches the subcontracted child its purchase order pays for (SCRUM-441, G9).
 *
 * <p>Before the receipt commits, in its transaction — not after commit like the module's other listeners:
 * decision Q8 says a subcontractor's goods stay the order's through receiving and QC, and after commit they would
 * already be free INBOUND stock, which a putaway turns into blanks for sale. Holding them here means they are
 * never free; a failure fails the confirmation instead of leaving them unheld. Not persisted by the event
 * publication registry (it keeps AFTER_COMMIT listeners only), and counted once per receipt anyway.</p>
 */
@Component("productionSubcontractReceiptListener")
class SubcontractReceiptListener {

    private final SubcontractService subcontracts;

    SubcontractReceiptListener(SubcontractService subcontracts) {
        this.subcontracts = subcontracts;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(GoodsReceiptConfirmed event) {
        subcontracts.onReceipt(event);
    }
}

package com.stockflow.production.internal.service;

import com.stockflow.contracts.GoodsReceiptConfirmed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A goods receipt reaches the subcontracted child its purchase order pays for (SCRUM-441, G9). After commit, in
 * its own transaction; counted once per receipt, so a retried publication changes nothing.
 */
@Component("productionSubcontractReceiptListener")
class SubcontractReceiptListener {

    private final SubcontractService subcontracts;

    SubcontractReceiptListener(SubcontractService subcontracts) {
        this.subcontracts = subcontracts;
    }

    @ApplicationModuleListener
    public void on(GoodsReceiptConfirmed event) {
        subcontracts.onReceipt(event);
    }
}

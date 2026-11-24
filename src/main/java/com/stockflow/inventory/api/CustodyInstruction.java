package com.stockflow.inventory.api;

/**
 * What a customer chose for goods held for them because their return refunded nothing (WF-05 Q-4: "Sale hỏi khách, ghi
 * lại một trong hai: gửi trả (khách chịu phí), hoặc khách đồng ý để công ty huỷ hộ (biên bản)"). Sales record it on the
 * return; the warehouse manager carries it out on the cartons, and on no other terms.
 */
public enum CustodyInstruction {
    /** Handed back to the customer, at their cost. */
    SEND_BACK,
    /** Destroyed on their behalf, with their written consent (a record names it). */
    DESTROY_ON_BEHALF
}

package com.stockflow.order.internal.domain;

/**
 * How goods kept on a custody note end (WF-05 Q-11 hỏi thêm: "Sale chọn: trả lại khách / mở yêu cầu bổ sung / khách
 * đồng ý huỷ hộ (biên bản)"; audit round 3, RAD-3). Goods no receipt takes - another SKU, more than approved, a parcel
 * after the refund was settled - are the customer's: kept for them, never stock, until one of these is done.
 */
public enum ReturnCustodyNoteChoice {
    /** Handed back to the customer, at their cost (the contract's terms). */
    SEND_BACK,
    /** Destroyed on their behalf, with their written consent (biên bản), which the choice's note names. */
    DESTROY_ON_BEHALF,
    /**
     * Taken on a supplementary return request Sales opened for them: once it is approved the warehouse receives the
     * goods on that return's receipt, refunded and invoiced there (Q-11 "Kết thúc giữ hộ").
     */
    TAKEN_BY_RETURN
}

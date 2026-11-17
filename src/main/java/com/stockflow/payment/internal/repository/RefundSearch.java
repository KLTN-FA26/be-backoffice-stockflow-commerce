package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.OrderRefund;
import com.stockflow.payment.internal.domain.RefundRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Paged lists of refunds for the accountant (4B-9, kltn-docs 15 §4.5). */
public interface RefundSearch {

    /** Refund records, newest first; every filter null means "any". */
    Page<RefundRecord> records(UUID customerId, UUID orderId, RefundRecord.Status status, Pageable pageable);

    /**
     * Orders with something refundable, most recently settled first; {@code outstanding} true keeps the ones not yet
     * refunded in full, false those refunded in full, null all of them.
     */
    Page<OrderRefund> orderRefunds(UUID customerId, Boolean outstanding, Pageable pageable);
}

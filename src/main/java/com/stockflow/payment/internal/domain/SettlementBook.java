package com.stockflow.payment.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of what cancellations and closures settled, and of the refunds recorded against them (4B-9, kltn-docs
 * 15 §4.5, §4.6). One port for both: an order's refunds are checked against its settlements, and
 * {@code tg_refund_record_total} holds them to each other at commit.
 */
public interface SettlementBook {

    /** The settlement of this cancellation or closure, if it was made already (a replay settles nothing twice). */
    Optional<OrderSettlement> find(OrderSettlement.Kind kind, UUID sourceId);

    /** The order's settlements, oldest first. */
    List<OrderSettlement> ofOrder(UUID orderId);

    /** Writes a new settlement and its reductions; its fee item, if any, is saved before. */
    void save(OrderSettlement settlement);

    /** Writes a new refund record. */
    RefundRecord record(RefundRecord record);

    /** Writes the void of a record read through {@link #lockRecord}. */
    RefundRecord saveVoided(RefundRecord record);

    Optional<RefundRecord> findRecord(UUID id);

    /** The record, its row locked until the transaction ends and read fresh. */
    Optional<RefundRecord> lockRecord(UUID id);

    /** The order's refund records, oldest first, voided ones included. */
    List<RefundRecord> recordsOf(UUID orderId);

    /** Whether a live refund record already names this outgoing statement line. */
    boolean refundReferenceInUse(String reference);
}

package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the goods receipts each production order has open (4A-5, {@code ordering.production_order_receipt},
 * kltn-docs 03 edge cases). A receipt is registered as a draft when it is started and settled once: confirmed when its
 * cartons are counted in, cancelled when the draft is. The database refuses a registration that drifts from its
 * receipt ({@code tg_goods_receipts_production_registered}).
 */
public interface ProductionOrderReceiptRepository {

    /** Where a registered receipt stands. */
    enum Status { DRAFT, CONFIRMED, CANCELLED }

    /** One receipt of a production order. */
    record Registered(UUID productionOrderId, UUID receiptId, String receiptNumber, Status status) {
    }

    Optional<Registered> find(UUID productionOrderId, UUID receiptId);

    /** Registers a draft receipt of the production order. */
    void open(UUID productionOrderId, UUID receiptId, String receiptNumber, Instant at);

    /** Settles a draft: {@code CONFIRMED} or {@code CANCELLED}. */
    void settle(UUID productionOrderId, UUID receiptId, Status status, Instant at);

    /** The numbers of the production order's draft receipts, in order; empty when none is open. */
    List<String> drafts(UUID productionOrderId);
}

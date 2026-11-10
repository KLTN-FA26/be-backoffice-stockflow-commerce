package com.stockflow.order.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What receiving against a production order asks of the order module (4A-5, kltn-docs 03 §4.1, 17 §4.3 step 11). The
 * goods receipt is procurement's; the production order, its progress and its status are this module's. Every method
 * runs in the caller's transaction.
 *
 * <p><b>Lock order.</b> Every write to a production order locks its sales order ({@code ordering.customer_order})
 * first and the production order after it - drawing one up, editing, submitting, approving, cancelling and closing
 * do, and so does {@link #lock} and every write here, which take both locks themselves even when the caller already
 * holds them. A receipt then follows with its own rows: sales order, production order, goods receipt
 * ({@code FOR UPDATE}), then inventory's SKU advisory locks. A path taking the production order before its sales order
 * would deadlock against an approval.</p>
 *
 * <p>The order module keeps which receipts each production order has open ({@link #openReceipt},
 * {@link #dropReceipt}, {@link #recordReceipt}): a production order with a draft receipt is neither cancelled nor
 * closed (03 edge cases).</p>
 */
public interface ProductionOrderReceiving {

    /** The production order as it reads now, unlocked; empty for an unknown id. */
    Optional<ReceivableProductionOrder> find(UUID productionOrderId);

    /** Several production orders as they read now, by id, for a page of receipts; unknown ids are left out. */
    Map<UUID, ReceivableProductionOrder> findAll(Collection<UUID> productionOrderIds);

    /**
     * The production order locked until the caller's transaction ends - its sales order first, then the production
     * order, read fresh after the lock. Counting and confirming a receipt decide BR-02 under it: two receipts of one
     * production order are counted one after the other.
     */
    Optional<ReceivableProductionOrder> lock(UUID productionOrderId);

    /**
     * A draft receipt was started against the production order: registered, so the production order is neither
     * cancelled nor closed while it is open.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCTION_ORDER_NOT_FOUND};
     *         {@code PRODUCTION_ORDER_NOT_RECEIVABLE} when goods cannot be received against it (03 BR-01, BR-15)
     */
    void openReceipt(UUID productionOrderId, UUID receiptId, String receiptNumber);

    /**
     * The draft receipt was cancelled, by {@code actorId}. Idempotent: a receipt already dropped changes nothing. When
     * it was the last draft of a production order whose sales order was cancelled meanwhile, the production order is
     * stopped now, as the cancellation would have done had no receipt been in progress (17 §4.8, BR-09).
     */
    void dropReceipt(UUID productionOrderId, UUID receiptId, UUID actorId);

    /**
     * The receipt was confirmed: {@code cartonsByProductionOrderLine} cartons of each line were counted in. The
     * production order moves on (17 §4.3 step 11): SENT or ACCEPTED with no sample to approve goes to IN_PRODUCTION
     * first (03 BR-15), then RECEIVED once every line is received in full, PARTIALLY_RECEIVED otherwise; a short receipt
     * never closes it (03 BR-10). Idempotent per receipt: a receipt already recorded changes nothing and the production
     * order is answered as it stands.
     *
     * @return the production order after the receipt
     * @throws com.stockflow.common.error.BusinessException {@code PRODUCTION_ORDER_NOT_FOUND};
     *         {@code PRODUCTION_ORDER_NOT_RECEIVABLE}; {@code RECEIPT_EXCEEDS_PRODUCTION_ORDER} when a line would be
     *         received beyond what it orders (03 BR-02)
     */
    ReceivableProductionOrder recordReceipt(UUID productionOrderId, UUID receiptId,
                                            Map<UUID, Integer> cartonsByProductionOrderLine, UUID actorId, Instant at);
}

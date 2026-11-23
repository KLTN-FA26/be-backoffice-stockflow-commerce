package com.stockflow.order.api;

import java.util.UUID;

/**
 * Procurement tells a return request that a parcel of its goods is being counted on a DRAFT return receipt, and when
 * that draft is confirmed or cancelled (WF-05 BE-23). While a draft is open the return's goods are not marked complete
 * ({@code RETURN_RECEIPT_DRAFT_OPEN}), its deadline does not end it, and no refund of it is proposed or approved: the
 * parcel's units would otherwise come after the refund was settled, or reopen a return the order had already left.
 *
 * <p>The order module cannot read procurement's receipts - procurement depends on {@code order :: api}, not the other
 * way round - so the receipt says it, in its own transaction: call each method inside the transaction that opens,
 * confirms or cancels the draft, before the receipt itself changes, so the two commit or fail together. Each takes the
 * order's row lock and then the return's, as every return step does (the global order: receipt row, order row, return
 * row, then the SKU advisory locks and stock rows a confirmation takes).</p>
 */
public interface ReturnReceiptTracking {

    /**
     * A DRAFT receipt was opened for a parcel of return {@code returnRequestId}. The same receipt again changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RETURN_REQUEST_NOT_FOUND};
     *         {@code RETURN_NOT_AWAITING_GOODS} when no receipt may be opened for it now (approved, refund not settled);
     *         {@code RETURN_RECEIPT_ALREADY_OPEN} while another draft counts a parcel of it
     */
    void draftOpened(UUID returnRequestId, UUID receiptId);

    /**
     * The DRAFT receipt was confirmed or cancelled: the return no longer waits for it. A receipt the return does not
     * name as its open draft (told twice, or drafted before BE-23) changes nothing.
     */
    void draftClosed(UUID returnRequestId, UUID receiptId);
}

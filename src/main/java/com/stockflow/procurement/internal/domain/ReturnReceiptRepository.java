package com.stockflow.procurement.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for the {@link ReturnReceipt} aggregate; the adapter maps the RETURN rows of {@code procurement.goods_receipts}. */
public interface ReturnReceiptRepository {

    Optional<ReturnReceipt> findById(UUID receiptId);

    /**
     * The receipt under its row lock ({@code SELECT ... FOR UPDATE}), read after the lock is taken: a scan and a
     * confirmation of one receipt are decided one after the other, the second seeing what the first committed.
     */
    Optional<ReturnReceipt> findByIdForUpdate(UUID receiptId);

    ReturnReceipt save(ReturnReceipt receipt);

    /**
     * The draft receipt of this returned shipment, if any ({@code uk_goods_receipts_return_shipment_draft}). The
     * carrier may bring a shipment back in several trips, each received on a receipt of its own (WF-05 Q-11), one being
     * counted at a time.
     */
    Optional<ReturnReceipt> findDraftForShipment(UUID shipmentId);

    /** The LPNs on this returned shipment's CLOSED receipts: what of it came back so far (WF-05 BE-24). */
    java.util.Set<String> lpnsReceivedForShipment(UUID shipmentId);

    /**
     * The units of {@code sku} back for return request {@code returnRequestId} on its receipts not cancelled - cartons
     * by their count, and loose units - leaving out the loose line {@code exceptLooseLine} (being recounted; null for
     * none). What WF-05 Q-18 caps at what the return approved.
     */
    long unitsReceivedForReturn(UUID returnRequestId, String sku, UUID exceptLooseLine);

    /**
     * Units of one SKU on one receipt of a return, cartons by their count and loose units together, as
     * {@link #unitsReceivedForReturn} counts them.
     */
    record ReceiptUnits(UUID receiptId, GoodsReceiptStatus status, String sku, long units) {
    }

    /**
     * Per receipt and SKU, the units on return request {@code returnRequestId}'s receipts not cancelled, receipt
     * {@code exceptReceipt} left out (the one being shown, counted from the aggregate): one query for every SKU, what
     * the receipt screen's progress and candidates are worked out from (WF-05 receiving progress).
     */
    java.util.List<ReceiptUnits> unitsOnOtherReceipts(UUID returnRequestId, UUID exceptReceipt);

    /** The LPNs on this return request's CLOSED receipts: what of it came back so far. */
    java.util.Set<String> lpnsReceivedForReturn(UUID returnRequestId);

    /**
     * The draft receipt of this return request, if any ({@code uk_goods_receipts_return_request_draft}). A return may
     * come back in several parcels, each on a receipt of its own (WF-05 TH-3), one being counted at a time.
     */
    Optional<ReturnReceipt> findDraftForReturnRequest(UUID returnRequestId);
}

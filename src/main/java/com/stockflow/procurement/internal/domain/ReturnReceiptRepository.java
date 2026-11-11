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

    /** The receipt not cancelled for this returned shipment, if any ({@code uk_goods_receipts_return_shipment}). */
    Optional<ReturnReceipt> findLiveForShipment(UUID shipmentId);

    /** The receipt not cancelled for this return request, if any ({@code uk_goods_receipts_return_request}). */
    Optional<ReturnReceipt> findLiveForReturnRequest(UUID returnRequestId);
}

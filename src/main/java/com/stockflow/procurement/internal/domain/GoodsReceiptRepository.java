package com.stockflow.procurement.internal.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for the {@link GoodsReceipt} aggregate; the adapter maps {@code procurement.goods_receipts}. */
public interface GoodsReceiptRepository {

    Optional<GoodsReceipt> findById(UUID receiptId);

    /**
     * The receipt under a row lock ({@code SELECT ... FOR UPDATE}), read after the lock is taken so a caller that waited
     * sees the status and lines the holder committed. Deciding whether a receipt is fully put away from anything older
     * misses a decision made while it waited.
     */
    Optional<GoodsReceipt> findByIdForUpdate(UUID receiptId);
    GoodsReceipt save(GoodsReceipt receipt);

    /** {@code GR-yyyyMMdd-nnnn}, numbered per business day. */
    String nextNumber(LocalDate day);

    /**
     * Units already received on other receipts of these PO lines, per line: the receipts that were posted - counted
     * in, whatever has happened to them since - the way {@code check_goods_receipt_tolerance} counts them for BR-02. A
     * draft, a receipt waiting for approval and a cancelled one have counted nothing in.
     */
    Map<UUID, Integer> receivedOnOtherReceipts(Iterable<UUID> poLineIds, UUID excludingReceiptId);

    /** Units counted in (receipt posted: not a draft, not waiting for approval, not cancelled) per line of one order. */
    Map<UUID, Integer> confirmedQuantities(UUID purchaseOrderId);
}

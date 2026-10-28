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
     * Units already on receipts of these PO lines, per line: every receipt that is not cancelled
     * counts, drafts included, the way {@code check_goods_receipt_line} counts them for BR-02.
     */
    Map<UUID, Integer> receivedOnOtherReceipts(Iterable<UUID> poLineIds, UUID excludingReceiptId);

    /** Units counted in (receipt confirmed, not draft or cancelled) per line of one order. */
    Map<UUID, Integer> confirmedQuantities(UUID purchaseOrderId);
}

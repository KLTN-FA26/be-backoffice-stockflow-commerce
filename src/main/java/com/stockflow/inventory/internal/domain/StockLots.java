package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Port: the lots received goods belong to ({@code inventory.stock_lot}, SCRUM-142). A lot is one goods receipt's
 * units of one SKU, lot number and expiry — so a lot number reused by another receipt is another lot.
 */
public interface StockLots {

    /**
     * The lot of units whose lot is not known (kltn-docs 20 BR-22, open question C45): every unit carries a lot
     * ({@code ck_stock_item_lot}, V20261113000300), so goods given none are of this one, as V20261111000110 labelled
     * the stock it boxed.
     */
    String UNTRACED = "UNTRACED";

    /** {@code lotNumber} trimmed, or {@link #UNTRACED} when none is given. */
    static String orUntraced(String lotNumber) {
        return lotNumber == null || lotNumber.isBlank() ? UNTRACED : lotNumber.trim();
    }

    /**
     * The lot a receipt opens for these goods: found when another line of the same receipt opened it already, made
     * otherwise. The caller holds the SKU's advisory lock, which is what keeps two lines of one receipt from making
     * it twice; {@code uk_stock_lot_receipt} is the last guard.
     */
    UUID openForReceipt(ReceiptLot lot);

    /**
     * Who supplied a lot, as its receipt recorded it: where damaged goods of the lot go back to
     * (RETURN_TO_SUPPLIER). Empty for an unknown lot, or one with no receipt behind it (UNTRACED).
     */
    java.util.Optional<LotSupplier> supplierOf(UUID lotId);

    /** The supplier a lot came from, snapshotted on the lot when it was received. */
    record LotSupplier(UUID supplierId, String supplierName) {
    }

    /** What a receipt knows about the goods it brings in, snapshotted onto the lot. */
    record ReceiptLot(Sku sku, String lotNumber, LocalDate expiryDate, Instant receivedAt, UUID goodsReceiptId,
                      String goodsReceiptNumber, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                      String supplierName, UUID warehouseId) {
    }
}

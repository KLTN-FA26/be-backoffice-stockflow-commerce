package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Port: the lots received goods belong to ({@code inventory.stock_lot}, SCRUM-142). Goods are received against
 * production orders only (4A-5, kltn-docs 03 BR-03): the lot is the production order's, one per production order and
 * SKU across all its receipts, numbered with its code. The other lots are UNTRACED, opened here
 * ({@link #openUntraced}) for stock received with no production order behind it: a carton in stock names its lot
 * ({@code ck_stock_package_lot}), so there is always one to name.
 */
public interface StockLots {

    /**
     * The lot of units whose lot is not known (kltn-docs 20 BR-22, open question C45): every unit carries a lot
     * ({@code ck_stock_package_lot}, V20261230000600), so goods given none are of this one, as V20261111000110 labelled
     * the stock it boxed.
     */
    String UNTRACED = "UNTRACED";

    /** {@code lotNumber} trimmed, or {@link #UNTRACED} when none is given. */
    static String orUntraced(String lotNumber) {
        return lotNumber == null || lotNumber.isBlank() ? UNTRACED : lotNumber.trim();
    }

    /**
     * The lot a receipt opens for these goods: the production order's lot of the SKU, found when an earlier line or
     * receipt of it opened one, made otherwise. The caller holds the SKU's advisory lock, which is what keeps two lines
     * from making it twice; {@code uk_stock_lot_production_order} is the last guard.
     */
    UUID openForReceipt(ReceiptLot lot);

    /**
     * The UNTRACED lot of goods received with no production order: the one of this SKU, lot number and receipt time
     * when an earlier line of the same receipt opened it, made otherwise. Until V20261230000600 a trigger on the stock
     * row did this ({@code resolve_stock_item_lot}); the carton has no lot number of its own to resolve one from, so
     * the receipt names the lot. The caller holds the SKU's advisory lock, which keeps two lines from making it twice.
     */
    UUID openUntraced(Sku sku, String lotNumber, Instant receivedAt);

    /** The production order a lot belongs to (4A-5), with its code; empty for a lot of any other origin or an unknown one. */
    java.util.Optional<ProductionLot> productionLotOf(UUID lotId);

    /** A lot opened for a production order: the order, and its code, which numbers the lot. */
    record ProductionLot(UUID productionOrderId, String productionOrderCode) {
    }

    /**
     * What a receipt knows about the goods it brings in, snapshotted onto the lot: the production order they were
     * received against (4A-5), whose code numbers the lot. All of it is set.
     */
    record ReceiptLot(Sku sku, Instant receivedAt, UUID warehouseId, UUID productionOrderId,
                      String productionOrderCode) {
        public ReceiptLot {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(receivedAt, "receivedAt");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(productionOrderId, "productionOrderId");
            Objects.requireNonNull(productionOrderCode, "productionOrderCode");
        }
    }
}

package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockLotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Package-private Spring Data over {@code inventory.stock_lot}; the module uses {@code StockLots}. */
interface StockLotJpaRepository extends JpaRepository<StockLotJpaEntity, UUID> {

    /** Every lot one receipt opened for a SKU: a handful at most (one per lot number and expiry). */
    List<StockLotJpaEntity> findByGoodsReceiptIdAndSku(UUID goodsReceiptId, String sku);
}

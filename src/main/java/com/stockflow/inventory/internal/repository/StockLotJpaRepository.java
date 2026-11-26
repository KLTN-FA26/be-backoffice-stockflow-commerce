package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.entity.StockLotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Package-private Spring Data over {@code inventory.stock_lot}; the module uses {@code StockLots}. */
interface StockLotJpaRepository extends JpaRepository<StockLotJpaEntity, UUID> {

    /** The lot of a production order's SKU (4A-5): one at most ({@code uk_stock_lot_production_order}). */
    Optional<StockLotJpaEntity> findByProductionOrderIdAndSku(UUID productionOrderId, String sku);

    /** The oldest first, as the trigger this replaces chose ({@code resolve_stock_item_lot}). */
    List<StockLotJpaEntity> findBySkuAndLotNumberAndReceivedAtAndOriginOrderByCreatedAtAscIdAsc(
            String sku, String lotNumber, Instant receivedAt, StockLotJpaEntity.Origin origin);
}

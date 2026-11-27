package com.stockflow.inventory.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.StockLots;
import com.stockflow.inventory.internal.entity.StockLotJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** {@link StockLots} on Spring Data. */
@Repository
class StockLotsAdapter implements StockLots {

    private final StockLotJpaRepository jpa;

    StockLotsAdapter(StockLotJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A production order lot names no goods receipt (4A-5): it outlives the receipt that opened it and gathers the
     * next ones, each of which its ledger lines name. Its number is the production order's code, whatever the line
     * said ({@code ck_stock_lot_production_order} holds both to it), and it is received when its first goods were.</p>
     */
    @Override
    public UUID openForReceipt(ReceiptLot lot) {
        return jpa.findByProductionOrderIdAndSku(lot.productionOrderId(), lot.sku().code())
                .map(StockLotJpaEntity::getId)
                .orElseGet(() -> jpa.saveAndFlush(StockLotJpaEntity.ofProductionOrder(Identifiers.newId(),
                        lot.sku().code(), lot.receivedAt(), lot.warehouseId(), lot.productionOrderId(),
                        lot.productionOrderCode())).getId());
    }

    @Override
    public UUID openUntraced(com.stockflow.common.domain.Sku sku, String lotNumber, java.time.Instant receivedAt) {
        return jpa.findBySkuAndLotNumberAndReceivedAtAndOriginOrderByCreatedAtAscIdAsc(sku.code(), lotNumber,
                        receivedAt, StockLotJpaEntity.Origin.UNTRACED).stream().findFirst()
                .map(StockLotJpaEntity::getId)
                .orElseGet(() -> jpa.saveAndFlush(StockLotJpaEntity.untraced(Identifiers.newId(), sku.code(),
                        lotNumber, receivedAt)).getId());
    }

    @Override
    public java.util.Optional<ProductionLot> productionLotOf(UUID lotId) {
        return lotId == null ? java.util.Optional.empty() : jpa.findById(lotId)
                .filter(lot -> lot.getProductionOrderId() != null)
                .map(lot -> new ProductionLot(lot.getProductionOrderId(), lot.getProductionOrderCode()));
    }
}

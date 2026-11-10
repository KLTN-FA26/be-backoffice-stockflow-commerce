package com.stockflow.inventory.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.StockLots;
import com.stockflow.inventory.internal.entity.StockLotJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.Objects;
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
        if (lot.productionOrderId() != null) {
            return jpa.findByProductionOrderIdAndSku(lot.productionOrderId(), lot.sku().code())
                    .map(StockLotJpaEntity::getId)
                    .orElseGet(() -> jpa.saveAndFlush(StockLotJpaEntity.ofProductionOrder(Identifiers.newId(),
                            lot.sku().code(), lot.receivedAt(), lot.warehouseId(), lot.productionOrderId(),
                            lot.productionOrderCode())).getId());
        }
        return jpa.findByGoodsReceiptIdAndSku(lot.goodsReceiptId(), lot.sku().code()).stream()
                .filter(found -> Objects.equals(found.getLotNumber(), lot.lotNumber()))
                .map(StockLotJpaEntity::getId)
                .findFirst()
                .orElseGet(() -> jpa.saveAndFlush(new StockLotJpaEntity(Identifiers.newId(), lot.sku().code(),
                        lot.lotNumber(), lot.receivedAt(), lot.goodsReceiptId(),
                        lot.goodsReceiptNumber(), lot.purchaseOrderId(), lot.purchaseOrderNumber(), lot.supplierId(),
                        lot.supplierName(), lot.warehouseId())).getId());
    }

    @Override
    public java.util.Optional<LotSupplier> supplierOf(UUID lotId) {
        if (lotId == null) {
            return java.util.Optional.empty();
        }
        return jpa.findById(lotId).filter(lot -> lot.getSupplierId() != null)
                .map(lot -> new LotSupplier(lot.getSupplierId(), lot.getSupplierName()));
    }
}

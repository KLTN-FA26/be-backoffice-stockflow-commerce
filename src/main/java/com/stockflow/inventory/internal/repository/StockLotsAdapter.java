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

    @Override
    public UUID openForReceipt(ReceiptLot lot) {
        return jpa.findByGoodsReceiptIdAndSku(lot.goodsReceiptId(), lot.sku().code()).stream()
                .filter(found -> Objects.equals(found.getLotNumber(), lot.lotNumber())
                        && Objects.equals(found.getExpiryDate(), lot.expiryDate()))
                .map(StockLotJpaEntity::getId)
                .findFirst()
                .orElseGet(() -> jpa.saveAndFlush(new StockLotJpaEntity(Identifiers.newId(), lot.sku().code(),
                        lot.lotNumber(), lot.expiryDate(), lot.receivedAt(), lot.goodsReceiptId(),
                        lot.goodsReceiptNumber(), lot.purchaseOrderId(), lot.purchaseOrderNumber(), lot.supplierId(),
                        lot.supplierName(), lot.warehouseId())).getId());
    }
}

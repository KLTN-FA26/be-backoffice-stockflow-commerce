package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;

public final class StockPolicy {
    private StockPolicy() {}
    public static void validateCount(InventoryPolicy p,CycleCountLine l) {
        boolean invalid=switch(p.trackingMode()) {
            case NONE -> l.lot()!=null || l.serial()!=null;
            case LOT -> l.lot()==null || l.lot().isBlank() || l.serial()!=null;
            case SERIAL -> l.serial()==null || l.serial().isBlank() || l.counted()>1;
        };
        if(invalid || p.expiryTracked()!=(l.expiry()!=null) || p.removalStrategy()==RemovalStrategy.FIFO && l.receivedAt()==null)
            throw new BusinessException(ErrorCode.COUNT_POLICY_CONFLICT);
    }
    public static InventoryPolicy validate(InventoryPolicy policy) {
        if (policy == null || policy.removalStrategy() == null || policy.trackingMode() == null
                || policy.reorderPoint() != null && policy.reorderPoint() < 0
                || policy.safetyStock() != null && policy.safetyStock() < 0
                || policy.reorderPoint() != null && policy.safetyStock() != null
                   && policy.safetyStock() > policy.reorderPoint()
                || policy.maxShelfLifeDays() != null && (policy.maxShelfLifeDays() < 1 || policy.maxShelfLifeDays() > 36500)
                || policy.expiryTracked() && (policy.trackingMode() == TrackingMode.NONE
                   || policy.removalStrategy() != RemovalStrategy.FEFO)
                || !policy.expiryTracked() && policy.maxShelfLifeDays() != null) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_INVALID);
        }
        return policy;
    }
}

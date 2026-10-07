package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;

public final class StockPolicy {
    private StockPolicy() {}

    public static InventoryPolicy validate(InventoryPolicy policy) {
        if (policy == null
                || policy.removalStrategy() == null
                || policy.trackingMode() == null
                || policy.reorderPoint() != null && policy.reorderPoint() < 0
                || policy.safetyStock() != null && policy.safetyStock() < 0
                || policy.reorderPoint() != null
                        && policy.safetyStock() != null
                        && policy.safetyStock() > policy.reorderPoint()
                || policy.maxShelfLifeDays() != null
                        && (policy.maxShelfLifeDays() < 1 || policy.maxShelfLifeDays() > 36500)
                || policy.expiryTracked()
                        && (!policy.trackingMode().lotTracked()
                                || policy.removalStrategy() != RemovalStrategy.FEFO)
                || !policy.expiryTracked() && policy.maxShelfLifeDays() != null) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_INVALID);
        }
        return policy;
    }
}

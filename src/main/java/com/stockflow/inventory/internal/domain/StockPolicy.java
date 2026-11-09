package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryPolicy;

public final class StockPolicy {
    private StockPolicy() {}

    public static InventoryPolicy validate(InventoryPolicy policy) {
        if (policy == null || policy.removalStrategy() == null || policy.trackingMode() == null) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_INVALID);
        }
        return policy;
    }
}

package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;

import java.util.List;

public final class StockPolicy {
    private StockPolicy() {}

    public static InventoryPolicy validate(InventoryPolicy policy) {
        if (policy == null
                || policy.removalStrategy() == null
                || policy.trackingMode() == null
                || policy.reorderPoint() != null && policy.reorderPoint() < 0
                || policy.safetyStock() != null && policy.safetyStock() < 0
                || policy.maxQty() != null && policy.maxQty() < 0
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
        // An order-up-to level under the reorder point or the safety stock would refill to a shortage. It is
        // named on its own field so the form can mark it. A negative one is malformed, like a negative threshold, and
        // was refused above with the generic code: this message would claim a threshold it is below.
        Integer max = policy.maxQty();
        if (max != null
                && (policy.reorderPoint() != null && max < policy.reorderPoint()
                        || policy.safetyStock() != null && max < policy.safetyStock())) {
            ErrorCode code = ErrorCode.INVENTORY_MAX_QTY_BELOW_THRESHOLD;
            throw new BusinessException(
                    ErrorCode.INVENTORY_POLICY_INVALID,
                    code.defaultMessage(),
                    List.of(new FieldError("maxQty", code.defaultMessage(), code.name())));
        }
        return policy;
    }
}

package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;

import java.util.List;

public final class StockPolicy {
    private StockPolicy() {}

    private static final String MAX_QTY_CODE = "INVENTORY_MAX_QTY_BELOW_THRESHOLD";

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
        // An order-up-to level under the reorder point or the safety stock would refill to a shortage. It is
        // named on its own field so the form can mark it; a negative one never gets here (@Min(0) on the request).
        Integer max = policy.maxQty();
        if (max != null
                && (max < 0
                        || policy.reorderPoint() != null && max < policy.reorderPoint()
                        || policy.safetyStock() != null && max < policy.safetyStock())) {
            String message = ErrorCode.INVENTORY_MAX_QTY_BELOW_THRESHOLD.defaultMessage();
            throw new BusinessException(
                    ErrorCode.INVENTORY_POLICY_INVALID,
                    message,
                    List.of(new FieldError("maxQty", message, MAX_QTY_CODE)));
        }
        return policy;
    }
}

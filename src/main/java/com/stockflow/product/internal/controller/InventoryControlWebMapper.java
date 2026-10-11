package com.stockflow.product.internal.controller;

import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.product.internal.controller.dto.InventoryControlResponse;
import com.stockflow.product.internal.controller.dto.UpdateInventoryControlRequest;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import org.springframework.stereotype.Component;

@Component
public class InventoryControlWebMapper {
    public InventoryPolicy toPolicy(UpdateInventoryControlRequest r) {
        return new InventoryPolicy(r.reorderPoint(), r.safetyStock(), r.removalStrategy(),
                r.trackingMode(), r.expiryTracked(), r.maxShelfLifeDays());
    }
    public InventoryControlResponse toResponse(SkuInventoryControlService.View v) {
        var p = v.policy(); var e = v.evaluation();
        return new InventoryControlResponse(v.skuId(), v.sku(), v.unitOfMeasure(), v.version(),
                p.reorderPoint(), p.safetyStock(), p.removalStrategy().name(), p.trackingMode().name(),
                p.expiryTracked(), p.maxShelfLifeDays(), e.usableOnHand(), e.reorderRequired(), e.belowSafetyStock());
    }
}

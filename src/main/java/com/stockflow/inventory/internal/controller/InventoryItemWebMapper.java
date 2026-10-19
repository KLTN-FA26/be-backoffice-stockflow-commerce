package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.internal.controller.dto.InventoryItemResponse;
import com.stockflow.inventory.internal.controller.dto.InventoryItemSummaryResponse;
import com.stockflow.inventory.internal.controller.dto.UpdateItemLogisticsRequest;
import com.stockflow.inventory.internal.controller.dto.UpdateItemPolicyRequest;
import com.stockflow.inventory.internal.service.InventoryItems;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class InventoryItemWebMapper {

    InventoryItemResponse toResponse(InventoryItems.Detail d) {
        var l = d.logistics();
        var p = d.policy();
        var e = d.evaluation();
        return new InventoryItemResponse(d.id(), d.sku(), d.version(),
                new InventoryItemResponse.Logistics(l.unitOfMeasure(), l.barcode(), l.weightKg(), l.lengthCm(),
                        l.widthCm(), l.heightCm(), l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(),
                        l.packageHeightCm(), l.packageCount(), l.packSize(), l.storageClass().name(),
                        l.requiresAdultSignature(), l.shippingRestrictionNote(), l.qcRequired()),
                new InventoryItemResponse.Policy(p.reorderPoint(), p.safetyStock(), p.maxQty(),
                        p.removalStrategy().name(), p.trackingMode().name(), p.expiryTracked(),
                        p.maxShelfLifeDays()),
                new InventoryItemResponse.Evaluation(e.usableOnHand(), e.reorderPoint(), e.safetyStock(),
                        e.reorderRequired(), e.belowSafetyStock()));
    }

    InventoryItemSummaryResponse toSummary(InventoryItems.Summary s) {
        return new InventoryItemSummaryResponse(s.id(), s.sku(), s.unitOfMeasure(), s.barcode(),
                s.storageClass().name(), s.trackingMode().name(), s.policyConfigured());
    }

    ItemLogistics toLogistics(UpdateItemLogisticsRequest r) {
        return new ItemLogistics(r.unitOfMeasure(), r.barcode(), r.weightKg(), r.lengthCm(), r.widthCm(),
                r.heightCm(), r.packageWeightKg(), r.packageLengthCm(), r.packageWidthCm(), r.packageHeightCm(),
                Objects.requireNonNullElse(r.packageCount(), 1), Objects.requireNonNullElse(r.packSize(), 1),
                r.storageClass(), r.requiresAdultSignature(), r.shippingRestrictionNote(), r.qcRequired());
    }

    InventoryPolicy toPolicy(UpdateItemPolicyRequest r) {
        return new InventoryPolicy(r.reorderPoint(), r.safetyStock(), r.maxQty(), r.removalStrategy(),
                r.trackingMode(), r.expiryTracked(), r.maxShelfLifeDays());
    }
}

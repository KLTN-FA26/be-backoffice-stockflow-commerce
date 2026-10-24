package com.stockflow.product.internal.controller;

import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.product.internal.controller.dto.CreateUomConversionRequest;
import com.stockflow.product.internal.controller.dto.SalesUnitResponse;
import com.stockflow.product.internal.controller.dto.SkuPurchasingRequest;
import com.stockflow.product.internal.controller.dto.SkuPurchasingResponse;
import com.stockflow.product.internal.controller.dto.UomConversionResponse;
import com.stockflow.product.internal.controller.dto.UpdateUomConversionRequest;
import com.stockflow.product.internal.service.SkuUnitService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Wire format of a SKU's purchasing information, units of measure and sales units. */
@Component
class SkuUnitWebMapper {

    SkuPurchasingResponse toResponse(UUID variantId, InventoryItemPurchasing item) {
        ItemPurchasing p = item.purchasing();
        return new SkuPurchasingResponse(variantId, item.sku(), item.version(), p.defaultSupplierId(),
                p.referencePurchasePrice(), p.referencePurchaseCurrency(), p.leadTimeDays(), p.moq());
    }

    ItemPurchasing toPurchasing(SkuPurchasingRequest r) {
        return new ItemPurchasing(r.defaultSupplierId(), r.referencePurchasePrice(), r.referencePurchaseCurrency(),
                r.leadTimeDays(), r.moq());
    }

    UomConversionResponse toResponse(UomConversion c) {
        return new UomConversionResponse(c.id(), c.sku(), c.unitOfMeasure(), c.factor(), c.base(), c.weightKg(),
                c.lengthCm(), c.widthCm(), c.heightCm(), c.defaultPurchase(), c.active(), c.version(), c.defaultShipping());
    }

    UomConversionDraft toDraft(CreateUomConversionRequest r) {
        return new UomConversionDraft(r.unitOfMeasure(), r.factor(), r.weightKg(), r.lengthCm(), r.widthCm(),
                r.heightCm(), Boolean.TRUE.equals(r.defaultPurchase()), true,
                Boolean.TRUE.equals(r.defaultShipping()));
    }

    UomConversionDraft toDraft(UpdateUomConversionRequest r) {
        return new UomConversionDraft(r.unitOfMeasure(), r.factor(), r.weightKg(), r.lengthCm(), r.widthCm(),
                r.heightCm(), r.defaultPurchase(), r.active(), r.defaultShipping());
    }

    SalesUnitResponse toResponse(SkuUnitService.SalesUnitView v) {
        return new SalesUnitResponse(v.id(), v.variantId(), v.uomConversionId(), v.unitOfMeasure(), v.factor(),
                v.base(), v.active(), v.price(), v.implicitDefault(), v.version());
    }
}

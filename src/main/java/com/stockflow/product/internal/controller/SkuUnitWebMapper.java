package com.stockflow.product.internal.controller;

import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.product.internal.controller.dto.CreateUomConversionRequest;
import com.stockflow.product.internal.controller.dto.SalesUnitResponse;
import com.stockflow.product.internal.controller.dto.UomConversionResponse;
import com.stockflow.product.internal.controller.dto.UpdateUomConversionRequest;
import com.stockflow.product.internal.service.SkuUnitService;
import org.springframework.stereotype.Component;


/** Wire format of a SKU's units of measure and sales units. */
@Component
class SkuUnitWebMapper {

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

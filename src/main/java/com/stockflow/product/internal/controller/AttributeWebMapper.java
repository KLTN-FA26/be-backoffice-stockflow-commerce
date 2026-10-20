package com.stockflow.product.internal.controller;

import com.stockflow.product.internal.controller.dto.AttributeResponse;
import com.stockflow.product.internal.controller.dto.AttributeValueResponse;
import com.stockflow.product.internal.controller.dto.CategoryAttributeResponse;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeValueView;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeView;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeView;
import org.springframework.stereotype.Component;

/** Hand-written: a one-to-one copy of the service view, with the enum rendered as its name. */
@Component
class AttributeWebMapper {

    AttributeResponse toResponse(AttributeView a) {
        return new AttributeResponse(a.id(), a.code(), a.name(), a.dataType().name(), a.unit(), a.filterable(),
                a.searchable(), a.sortOrder(), a.active(), a.version(),
                a.values().stream().map(this::toResponse).toList());
    }

    CategoryAttributeResponse toResponse(CategoryAttributeView v) {
        return new CategoryAttributeResponse(v.attributeId(), v.attributeCode(), v.attributeName(),
                v.dataType().name(), v.attributeActive(), v.defaultRole().name(), v.required(), v.sortOrder());
    }

    private AttributeValueResponse toResponse(AttributeValueView v) {
        return new AttributeValueResponse(v.id(), v.code(), v.label(), v.valueNumber(), v.hexColor(),
                v.sortOrder(), v.active(), v.version());
    }
}

package com.stockflow.product.internal.controller;

import com.stockflow.product.internal.controller.dto.ProductAttributesResponse;
import com.stockflow.product.internal.controller.dto.ReplaceAxesRequest;
import com.stockflow.product.internal.controller.dto.ReplaceDescriptiveAttributesRequest;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.DescriptiveSpec;
import com.stockflow.product.internal.service.ProductAttributeService.AxisView;
import com.stockflow.product.internal.service.ProductAttributeService.DescriptiveView;
import com.stockflow.product.internal.service.ProductAttributeService.OptionView;
import com.stockflow.product.internal.service.ProductAttributeService.ProductAttributesView;
import com.stockflow.product.internal.service.ProductAttributeService.ValueRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Hand-written. Requests default a missing {@code sortOrder} to the entry position, so a client that
 * sends the list in display order never has to number it.
 */
@Component
class ProductAttributeWebMapper {

    List<AxisSpec> toAxisSpecs(ReplaceAxesRequest r) {
        List<ReplaceAxesRequest.Axis> axes = r.axes();
        return IntStream.range(0, axes.size()).mapToObj(i -> {
            ReplaceAxesRequest.Axis a = axes.get(i);
            return new AxisSpec(a.attributeId(), a.sortOrder() != null ? a.sortOrder() : i, a.optionValueIds());
        }).toList();
    }

    List<DescriptiveSpec> toDescriptiveSpecs(ReplaceDescriptiveAttributesRequest r) {
        List<ReplaceDescriptiveAttributesRequest.Entry> entries = r.attributes();
        return IntStream.range(0, entries.size()).mapToObj(i -> {
            ReplaceDescriptiveAttributesRequest.Entry e = entries.get(i);
            return new DescriptiveSpec(e.attributeId(), e.sortOrder() != null ? e.sortOrder() : i, e.valueIds(),
                    e.text(), e.number(), e.bool());
        }).toList();
    }

    ProductAttributesResponse toResponse(ProductAttributesView v) {
        return new ProductAttributesResponse(v.productId(),
                v.axes().stream().map(this::toResponse).toList(),
                v.descriptives().stream().map(this::toResponse).toList());
    }

    private ProductAttributesResponse.Axis toResponse(AxisView a) {
        return new ProductAttributesResponse.Axis(a.attributeId(), a.code(), a.name(), a.dataType().name(),
                a.sortOrder(), a.options().stream().map(this::toResponse).toList());
    }

    private ProductAttributesResponse.Option toResponse(OptionView o) {
        return new ProductAttributesResponse.Option(o.valueId(), o.code(), o.label(), o.hexColor(),
                o.valueNumber(), o.active(), o.usedByVariant());
    }

    private ProductAttributesResponse.Descriptive toResponse(DescriptiveView d) {
        return new ProductAttributesResponse.Descriptive(d.attributeId(), d.code(), d.name(), d.dataType().name(),
                d.unit(), d.sortOrder(), d.values().stream().map(this::toResponse).toList(),
                d.text(), d.number(), d.bool());
    }

    private ProductAttributesResponse.Value toResponse(ValueRef v) {
        return new ProductAttributesResponse.Value(v.valueId(), v.code(), v.label(), v.active());
    }
}

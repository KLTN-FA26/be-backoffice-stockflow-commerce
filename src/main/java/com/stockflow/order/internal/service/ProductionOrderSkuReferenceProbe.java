package com.stockflow.order.internal.service;

import com.stockflow.order.internal.repository.ProductionOrderLineReferences;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The order module's answer to "what is still on its way for these SKUs" (kltn-docs 01 BR-14): the lines of open
 * production orders not fully received. It took over from procurement's open purchase order lines when purchasing went
 * (4A-8): goods now arrive only against production orders. Handed to the product service by Spring; see
 * {@link SkuReferenceProbe} for why product does not call order itself.
 */
@Component
class ProductionOrderSkuReferenceProbe implements SkuReferenceProbe {

    private final ProductionOrderLineReferences lines;

    ProductionOrderSkuReferenceProbe(ProductionOrderLineReferences lines) {
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuReference> openReferences(Collection<String> skus, int limit) {
        return lines.openLines(skus, limit).stream()
                .map(row -> new SkuReference(SkuReference.Kind.PRODUCTION_ORDER_LINE, row.code(),
                        row.productionOrderId(), row.sku()))
                .toList();
    }
}

package com.stockflow.order.internal.service;

import com.stockflow.order.internal.repository.OrderLineReferences;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The order module's answer to "what is still open against these SKUs" (SCRUM-461, BR-PRD-005): the lines of
 * orders that have been placed and not yet shipped. Handed to the product service by Spring; see
 * {@link SkuReferenceProbe} for why product does not call order itself.
 */
@Component
class OrderSkuReferenceProbe implements SkuReferenceProbe {

    private final OrderLineReferences lines;

    OrderSkuReferenceProbe(OrderLineReferences lines) {
        this.lines = lines;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkuReference> openReferences(Collection<String> skus, int limit) {
        return lines.unshippedLines(skus, limit).stream()
                .map(row -> new SkuReference(SkuReference.Kind.SALES_ORDER_LINE, row.orderNumber(), row.orderId(),
                        row.sku()))
                .toList();
    }
}

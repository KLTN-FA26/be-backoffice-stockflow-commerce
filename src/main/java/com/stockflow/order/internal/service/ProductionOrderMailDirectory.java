package com.stockflow.order.internal.service;

import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ProductionOrderMail;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What notification reads when it writes a message to a producer (4A-3): the order module's side of
 * {@link ProductionOrderDirectory}, whose javadoc says why the dependency runs this way.
 */
@Component
class ProductionOrderMailDirectory implements ProductionOrderDirectory {

    private final ProductionOrderDispatch dispatch;

    ProductionOrderMailDirectory(ProductionOrderDispatch dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public Optional<ProductionOrderMail> mail(UUID productionOrderId, Instant issuedAt) {
        return dispatch.mail(productionOrderId, issuedAt);
    }
}

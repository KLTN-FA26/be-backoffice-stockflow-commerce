package com.stockflow.order.internal.service;

import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ProductionOrderMail;
import com.stockflow.notification.api.ShortSalesOrder;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * What notification reads when it writes a message to a producer (4A-3): the order module's side of
 * {@link ProductionOrderDirectory}, whose javadoc says why the dependency runs this way.
 */
@Component
class ProductionOrderMailDirectory implements ProductionOrderDirectory {

    private final ProductionOrderDispatch dispatch;
    private final ProductionOrderRepository productionOrders;
    private final OrderRepository orders;
    private final ShippingWarehouseService shippingWarehouses;

    ProductionOrderMailDirectory(ProductionOrderDispatch dispatch, ProductionOrderRepository productionOrders,
                                 OrderRepository orders, ShippingWarehouseService shippingWarehouses) {
        this.dispatch = dispatch;
        this.productionOrders = productionOrders;
        this.orders = orders;
        this.shippingWarehouses = shippingWarehouses;
    }

    @Override
    public Optional<ProductionOrderMail> mail(UUID productionOrderId, Instant issuedAt) {
        return dispatch.mail(productionOrderId, issuedAt);
    }

    @Override
    public Optional<ProductionOrderMail> stop(UUID productionOrderId) {
        return dispatch.stopMail(productionOrderId);
    }

    /**
     * Read without locks, for a message: the sales order's stock coverage at its shipping warehouse as the order's
     * own screens show it, narrowed to the SKUs of the production order. An order with no signed contract has no
     * coverage to be short of.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ShortSalesOrder> shortSalesOrder(UUID productionOrderId) {
        return productionOrders.findById(productionOrderId).flatMap(production -> orders
                .findById(new OrderId(production.salesOrderId())).filter(order -> !order.status().isTerminal())
                .filter(com.stockflow.order.internal.domain.Order::paidByContract).flatMap(order -> {
                    Set<String> skus = production.lines().stream()
                            .map(com.stockflow.order.internal.domain.ProductionOrder.Line::sku)
                            .collect(Collectors.toSet());
                    int missing = shippingWarehouses.coverage(order.id().value()).lines().stream()
                            .filter(line -> skus.contains(line.sku()))
                            .mapToInt(ShippingWarehouseService.LineCoverage::shortCartons).sum();
                    return missing > 0 ? Optional.of(new ShortSalesOrder(order.orderNumber().value(), missing))
                            : Optional.empty();
                }));
    }
}

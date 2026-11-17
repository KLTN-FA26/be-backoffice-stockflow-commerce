package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStockCoverage;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OrderStockCoverage} over {@link ShippingWarehouseService#coverage}, the very figures the stock coverage screen
 * reads, so the screen and this count held cartons one way. An order without a signed contract is answered as empty
 * here instead of refused as on the screen: a caller asking about any order's progress has nothing to correct.
 */
@Service
@Transactional(readOnly = true)
class OrderStockCoverageImpl implements OrderStockCoverage {

    private final OrderRepository orders;
    private final ShippingWarehouseService shippingWarehouses;

    OrderStockCoverageImpl(OrderRepository orders, ShippingWarehouseService shippingWarehouses) {
        this.orders = orders;
        this.shippingWarehouses = shippingWarehouses;
    }

    @Override
    public Optional<Coverage> coverage(UUID orderId) {
        if (orderId == null || orders.findById(new OrderId(orderId)).filter(Order::paidByContract).isEmpty()) {
            return Optional.empty();
        }
        ShippingWarehouseService.Coverage coverage = shippingWarehouses.coverage(orderId);
        return Optional.of(new Coverage(coverage.warehouseId() != null, coverage.warehouseCode(),
                coverage.fullyHeld(), lines(coverage.lines()),
                coverage.batches().stream().map(batch -> new Batch(batch.seq(), batch.windowStart(),
                        batch.windowEnd(), batch.fullyHeld(), lines(batch.lines()))).toList()));
    }

    private static List<Line> lines(List<ShippingWarehouseService.LineCoverage> lines) {
        return lines.stream().map(line -> new Line(line.sku(), line.cartons(), line.heldCartons(),
                line.shortCartons())).toList();
    }
}

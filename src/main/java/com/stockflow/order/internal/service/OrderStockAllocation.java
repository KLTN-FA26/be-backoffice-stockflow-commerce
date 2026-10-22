package com.stockflow.order.internal.service;

import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.internal.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * F-FUL step 02 from the order's side (SCRUM-152): inventory turns the order's SKU reservations into allocations
 * in the warehouse it ships from, and the order's lines swap each reservation id for the ids of the allocations
 * that replaced it — the ids fulfillment picks and a cancellation releases from then on. Shared by the two ways
 * an order reaches a warehouse: its release, and a stock-only order picked straight from CONFIRMED.
 *
 * <p>Runs in the caller's transaction, under the order's row lock, so the allocations and the order rows that
 * name them commit together; a warehouse that cannot cover a line throws and leaves both as they were.</p>
 */
@Component
class OrderStockAllocation {

    private static final Logger log = LoggerFactory.getLogger(OrderStockAllocation.class);

    private final InventoryService inventory;

    OrderStockAllocation(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** @return how many reservations were allocated; 0 for an order allocated already or placed before SCRUM-152 */
    int allocate(Order order, String warehouseCode) {
        AllocateStockResult result = inventory.allocate(new AllocateStockCommand(order.id().value(), warehouseCode));
        for (AllocateStockResult.Allocation allocation : result.allocations()) {
            order.replaceReservation(allocation.reservationId(), allocation.holdIds());
        }
        if (!result.allocations().isEmpty()) {
            log.info("Order {}: {} reservation(s) allocated in {}", order.orderNumber(), result.allocations().size(),
                    result.warehouseCode());
        }
        return result.allocations().size();
    }
}

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
    private final com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords;
    private final com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures;

    OrderStockAllocation(InventoryService inventory,
                         com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords,
                         com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures) {
        this.inventory = inventory;
        this.batchRecords = batchRecords;
        this.deliveryFailures = deliveryFailures;
    }

    /** @return how many reservations were allocated; 0 for an order allocated already or placed before SCRUM-152 */
    int allocate(Order order, String warehouseCode) {
        return allocate(order, warehouseCode, null);
    }

    /**
     * 4B-8 (17 BR-29): only {@code reservationIds} - one delivery batch's holds; null: every hold of the order. The
     * allocations replacing a hold stand for its batch from then on.
     */
    int allocate(Order order, String warehouseCode, java.util.Set<java.util.UUID> reservationIds) {
        AllocateStockResult result = inventory.allocate(new AllocateStockCommand(order.id().value(), warehouseCode,
                reservationIds));
        for (AllocateStockResult.Allocation allocation : result.allocations()) {
            order.replaceReservation(allocation.reservationId(), allocation.holdIds());
            batchRecords.carry(allocation.reservationId(), allocation.holdIds());
            // A hold taken for goods that came back stands for its delivery failure case after release too (WF-05
            // fix plan, section 2); nothing when it stood for none.
            deliveryFailures.carry(allocation.reservationId(), allocation.holdIds());
        }
        if (!result.allocations().isEmpty()) {
            log.info("Order {}: {} reservation(s) allocated in {}", order.orderNumber(), result.allocations().size(),
                    result.warehouseCode());
        }
        return result.allocations().size();
    }
}

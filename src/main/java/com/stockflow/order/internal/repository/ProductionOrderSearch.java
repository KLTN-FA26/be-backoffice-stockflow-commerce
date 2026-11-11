package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

/** The list of production orders (4A-2). */
public interface ProductionOrderSearch {

    /**
     * Every filter null means "any". {@code warehouses} not null: only production orders received at one of them (a
     * warehouse-bound caller's warehouses).
     */
    Page<ProductionOrder> search(UUID receivingWarehouseId, ProductionOrderStatus status, UUID producerId,
                                 UUID salesOrderId, Collection<UUID> warehouses, Pageable pageable);
}

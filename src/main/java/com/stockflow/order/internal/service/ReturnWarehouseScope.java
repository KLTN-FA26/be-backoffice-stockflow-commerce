package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * BR-SEC-002 for one return, read or written by the back office: staff limited to warehouses act on the returns of
 * orders their warehouses ship, and on no other (review of #195, N10; WF-05 audit round 3, RAD-2).
 *
 * <p>One guard for every staff path that reaches a return by its id - its detail, a photo's link, a custody note and its
 * outcome. Before audit round 2 only Sales (scope ALL) could write a custody note, so its path needed no check; the
 * M4 fix granted {@code sales-rma-custody:CREATE} to the warehouse roles, and a clerk of warehouse A then wrote onto,
 * and read back in full, a return of warehouse B that {@code GET /returns/{id}} refused them. A path that asks here
 * cannot drift from the read.</p>
 *
 * <p>The limit is the caller's own scope ({@link OrderWarehouseVisibility#limitOf}), not
 * {@code WarehouseScope.restriction()}: the photo link is declared scope OWN for the customer, and the custody
 * endpoints ALL; under either {@code restriction()} says nothing about a WAREHOUSE-scoped caller.</p>
 */
@Component
public class ReturnWarehouseScope {

    private final OrderRepository orders;

    ReturnWarehouseScope(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Refuses a back-office caller limited to warehouses none of which ships the return's order. A portal caller
     * ({@code ownCustomerId} set) is settled by the owner check, and a caller in no request (a listener, a job) is not
     * limited.
     *
     * @throws BusinessException {@code OUT_OF_DATA_SCOPE}, as {@code GET /returns/{id}} answers
     */
    public void require(UUID orderId, UUID ownCustomerId) {
        if (ownCustomerId != null) {
            return;
        }
        Set<UUID> limit = DataScopeContext.current().map(DataScopeContext.Scope::user)
                .map(OrderWarehouseVisibility::limitOf).orElse(null);
        if (limit == null) {
            return;
        }
        UUID warehouse = orders.findById(new OrderId(orderId)).map(Order::warehouseId).orElse(null);
        if (warehouse == null || !limit.contains(warehouse)) {
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE,
                    "Warehouse " + warehouse + " is not one the caller is assigned to");
        }
    }

    /**
     * The warehouses the back-office caller is limited to, or null when they are not (scope ALL, or no request): what a
     * list of returns filters on, so it shows exactly the returns {@link #require} lets through.
     */
    public Set<UUID> limit() {
        return DataScopeContext.current().map(DataScopeContext.Scope::user)
                .map(OrderWarehouseVisibility::limitOf).orElse(null);
    }
}

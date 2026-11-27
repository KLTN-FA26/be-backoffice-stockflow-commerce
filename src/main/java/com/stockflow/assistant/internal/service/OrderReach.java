package com.stockflow.assistant.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The warehouses whose orders a caller may read, as {@code ListOrdersQuery.warehouseIds} takes them: the rule
 * {@code GET /api/v1/orders} applies through the order module's {@code OrderWarehouseVisibility.limitOf}, restated here
 * because that class is the order module's own. A warehouse manager holds {@code sales-orders:READ} but reads only the
 * orders of their warehouses (team plan 5-7); a tool that listed orders without this limit answered them with every
 * warehouse's orders, which their screen never shows.
 */
final class OrderReach {

    private OrderReach() {
    }

    /**
     * Null when the caller is not limited (scope ALL, their broadest role wins); for a caller limited to warehouses,
     * their warehouses, empty when they have none, which matches no order. Never null for a limited caller: null would
     * mean every order.
     */
    static Set<UUID> limitOf(Optional<CurrentUser> caller) {
        return caller.filter(user -> user.scope() == DataScope.WAREHOUSE)
                .map(user -> user.warehouseIds() == null ? Set.<UUID>of() : Set.copyOf(user.warehouseIds()))
                .orElse(null);
    }
}

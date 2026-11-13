package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Which orders a back-office caller limited to warehouses may read (team plan 5-7, review M1). The warehouse manager
 * releases the orders of their shipping warehouse and records their delivery (kltn-docs 17 §2, BR-03, BR-27), so they
 * hold {@code sales-orders:READ}; but their role's scope is WAREHOUSE (00 §2.5.3: "only the data of the warehouses
 * assigned"), so they read only orders that ship from one of their warehouses (4B-7) or were released to one.
 *
 * <p>Why not the generic data-scope filter: an order row has no warehouse attribute for it (it has none until a
 * shipping warehouse is chosen or it is released), and the order endpoints declare scope ALL or OWN, under which
 * {@code WarehouseScope} does not limit. So the order controller asks here, explicitly.</p>
 */
@Service
@Transactional(readOnly = true)
public class OrderWarehouseVisibility {

    private final OrderSearchRepository search;

    OrderWarehouseVisibility(OrderSearchRepository search) {
        this.search = search;
    }

    /**
     * The warehouses {@code user} is limited to, or null when they are not limited (scope ALL - their broadest role
     * wins, 00 §2.5.3 rule 6). A limited user assigned no warehouse gets an empty set, which matches no order: never
     * null, which would mean every order.
     */
    public static Set<UUID> limitOf(CurrentUser user) {
        if (user == null || user.scope() != DataScope.WAREHOUSE) {
            return null;
        }
        return user.warehouseIds() == null ? Set.of() : Set.copyOf(user.warehouseIds());
    }

    /** Whether {@code user} may read the order: not limited, or the order is of one of their warehouses. */
    public boolean visible(UUID orderId, CurrentUser user) {
        Set<UUID> limit = limitOf(user);
        return limit == null || search.inWarehouses(orderId, limit);
    }
}

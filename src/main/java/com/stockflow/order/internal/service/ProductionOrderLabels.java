package com.stockflow.order.internal.service;

import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.repository.ProductionOrderReferences;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The names a production order shows beside its ids (A13): its sales order's number, its customer, its producer, its
 * receiving warehouse and the people who drew it up, approved or sent it and moved its status. Without them the screen
 * showed eight characters of each id, or needed the order, customer, warehouse and user permissions to say more. Five
 * reads for a whole page, whatever its size - never one per production order.
 */
@Service
@Transactional(readOnly = true)
public class ProductionOrderLabels {

    private final ProductionOrderReferences references;
    private final CustomerService customers;
    private final WarehouseService warehouses;
    private final IdentityService identities;

    ProductionOrderLabels(ProductionOrderReferences references, CustomerService customers, WarehouseService warehouses,
                          IdentityService identities) {
        this.references = references;
        this.customers = customers;
        this.warehouses = warehouses;
        this.identities = identities;
    }

    /** What was found for a page of production orders; every lookup answers null for an id it does not know. */
    public record Labels(Map<UUID, String> orderNumbers, Map<UUID, CustomerLabel> customerLabels,
                         Map<UUID, String> producerNames, Map<UUID, WarehouseView> warehouseViews,
                         Map<UUID, String> people) {

        public static final Labels NONE = new Labels(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        public String orderNumber(UUID id) {
            return get(orderNumbers, id);
        }

        public CustomerLabel customer(UUID id) {
            return get(customerLabels, id);
        }

        public String producer(UUID id) {
            return get(producerNames, id);
        }

        public WarehouseView warehouse(UUID id) {
            return get(warehouseViews, id);
        }

        public String person(UUID id) {
            return get(people, id);
        }

        private static <T> T get(Map<UUID, T> map, UUID id) {
            return id == null || map == null ? null : map.get(id);
        }
    }

    /** The labels of {@code orders}, and of the actors of {@code history} (null: no history is shown). */
    public Labels of(Collection<ProductionOrder> orders, List<ProductionOrderRepository.HistoryEntry> history) {
        if (orders.isEmpty()) {
            return Labels.NONE;
        }
        Set<UUID> people = new HashSet<>();
        orders.forEach(p -> {
            people.add(p.drawnUpBy());
            people.add(p.approvedBy());
            people.add(p.sentBy());
        });
        if (history != null) {
            history.forEach(h -> people.add(h.by()));
        }
        people.remove(null);
        return new Labels(references.orderNumbers(ids(orders, ProductionOrder::salesOrderId)),
                customers.labels(ids(orders, ProductionOrder::customerId)),
                references.producerNames(ids(orders, ProductionOrder::producerId)),
                warehouses.findWarehouses(ids(orders, ProductionOrder::receivingWarehouseId)),
                people.isEmpty() ? Map.of() : identities.displayNames(people));
    }

    private static List<UUID> ids(Collection<ProductionOrder> orders, Function<ProductionOrder, UUID> id) {
        return orders.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }
}

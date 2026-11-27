package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.api.ReleasedBatchOfOrder;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The orders waiting for a fulfillment task. First released, first offered (kltn-docs 07 step 1: tasks follow the
 * order of release); the order number breaks a tie so a page boundary never moves between two reads. Orders awaiting
 * stock whose part held may be picked before their release (07 §4.1 step 1) come after them.
 */
@Service
@Transactional(readOnly = true)
class FulfillmentCandidateService implements FulfillmentCandidates {

    private static final Sort FIRST_RELEASED = Sort.by(Sort.Order.asc("releasedAt"), Sort.Order.asc("orderNumber"));

    private final OrderSearchRepository search;
    private final CustomerService customers;
    private final DeliveryBatchRecords batchRecords;
    private final OrderRepository orders;
    private final ShippingWarehouseService shippingWarehouses;
    private final SalesContractRepository contracts;

    FulfillmentCandidateService(OrderSearchRepository search, CustomerService customers,
                                DeliveryBatchRecords batchRecords, OrderRepository orders,
                                ShippingWarehouseService shippingWarehouses, SalesContractRepository contracts) {
        this.search = search;
        this.customers = customers;
        this.batchRecords = batchRecords;
        this.orders = orders;
        this.shippingWarehouses = shippingWarehouses;
        this.contracts = contracts;
    }

    @Override
    public List<ReleasedBatchOfOrder> lastReleasedBatches(UUID warehouseId) {
        return batchRecords.lastReleasePerPartiallyFulfilled(warehouseId);
    }

    @Override
    public PageResponse<FulfillmentCandidate> awaitingTask(UUID warehouseId, Collection<UUID> awaitingBatchOrders,
                                                           int page, int size) {
        // Orders awaiting stock are not released, so last (a null release sorts last): released orders are served
        // first (07 §4.1 step 1).
        var candidates = search.awaitingTask(warehouseId, awaitingBatchOrders, prePickable(warehouseId),
                Pages.of(page, size, FIRST_RELEASED));
        // One query for every customer on the page (review of #150), not one per row.
        Map<UUID, String> names = customers.fullNames(candidates.getContent().stream()
                .map(FulfillmentCandidate::customerId).filter(java.util.Objects::nonNull).distinct().toList());
        return Pages.toResponse(candidates.map(candidate -> candidate.withCustomerName(names.get(candidate.customerId()))));
    }

    /**
     * 07 §4.1 step 1 ("đơn Awaiting Stock có ít nhất một dòng đã phân bổ được thùng"), 17 §4.4 step 15: the orders
     * awaiting stock whose next delivery batch has cartons held at the shipping warehouse - at least one, not all (a
     * batch held in full is released, 17 §4.4 step 16). Few orders wait for stock at a warehouse; each is read once.
     */
    private List<UUID> prePickable(UUID warehouseId) {
        List<UUID> found = new ArrayList<>();
        for (UUID orderId : search.awaitingStockWithoutTask(warehouseId)) {
            Order order = orders.findById(new OrderId(orderId)).orElse(null);
            if (order == null) {
                continue;
            }
            shippingWarehouses.nextBatch(order).filter(next -> !next.fullyHeld())
                    .flatMap(next -> contracts.findLiveByOrderId(orderId).stream().flatMap(c -> c.schedule().stream())
                            .filter(b -> b.id().equals(next.batchId())).findFirst())
                    .filter(batch -> !shippingWarehouses.heldFor(order, batch).isEmpty())
                    .ifPresent(batch -> found.add(orderId));
        }
        return found;
    }
}

package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.order.api.ReleasedBatchOfOrder;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The orders waiting for a fulfillment task. First released, first offered (kltn-docs 07 step 1: tasks follow the
 * order of release); the order number breaks a tie so a page boundary never moves between two reads.
 */
@Service
@Transactional(readOnly = true)
class FulfillmentCandidateService implements FulfillmentCandidates {

    private static final Sort FIRST_RELEASED = Sort.by(Sort.Order.asc("releasedAt"), Sort.Order.asc("orderNumber"));

    private final OrderSearchRepository search;
    private final CustomerService customers;
    private final DeliveryBatchRecords batchRecords;

    FulfillmentCandidateService(OrderSearchRepository search, CustomerService customers,
                                DeliveryBatchRecords batchRecords) {
        this.search = search;
        this.customers = customers;
        this.batchRecords = batchRecords;
    }

    @Override
    public List<ReleasedBatchOfOrder> lastReleasedBatches(UUID warehouseId) {
        return batchRecords.lastReleasePerPartiallyFulfilled(warehouseId);
    }

    @Override
    public PageResponse<FulfillmentCandidate> awaitingTask(UUID warehouseId, Collection<UUID> awaitingBatchOrders,
                                                           int page, int size) {
        var candidates = search.awaitingTask(warehouseId, awaitingBatchOrders, Pages.of(page, size, FIRST_RELEASED));
        // One query for every customer on the page (review of #150), not one per row.
        Map<UUID, String> names = customers.fullNames(candidates.getContent().stream()
                .map(FulfillmentCandidate::customerId).filter(java.util.Objects::nonNull).distinct().toList());
        return Pages.toResponse(candidates.map(candidate -> candidate.withCustomerName(names.get(candidate.customerId()))));
    }
}

package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.order.api.FulfillmentCandidate;
import com.stockflow.order.api.FulfillmentCandidates;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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

    FulfillmentCandidateService(OrderSearchRepository search, CustomerService customers) {
        this.search = search;
        this.customers = customers;
    }

    @Override
    public PageResponse<FulfillmentCandidate> readyToFulfill(UUID warehouseId, int page, int size) {
        var candidates = search.readyToFulfill(warehouseId, Pages.of(page, size, FIRST_RELEASED));
        // One look-up per customer on the page, not per row: a page is bounded (Pages.MAX_PAGE_SIZE).
        Map<UUID, String> names = new HashMap<>();
        candidates.forEach(candidate -> names.computeIfAbsent(candidate.customerId(),
                id -> customers.findById(id).map(CustomerSummary::fullName).orElse(null)));
        return Pages.toResponse(candidates.map(candidate -> candidate.withCustomerName(names.get(candidate.customerId()))));
    }
}

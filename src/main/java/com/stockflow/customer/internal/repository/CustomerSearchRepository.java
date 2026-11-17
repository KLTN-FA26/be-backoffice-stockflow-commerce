package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.CustomerSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerSearchRepository {
    Page<CustomerSummary> search(String search, String status, java.util.UUID segmentId, Pageable pageable);

    /** Full names by customer id, in one query; unknown ids are absent. */
    java.util.Map<java.util.UUID, String> fullNames(java.util.Collection<java.util.UUID> customerIds);

    /** Name and code by customer id, in one query; unknown ids are absent. */
    java.util.Map<java.util.UUID, com.stockflow.customer.api.CustomerLabel> labels(
            java.util.Collection<java.util.UUID> customerIds);
}

package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.CustomerSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerSearchRepository {
    Page<CustomerSummary> search(String search, String status, Pageable pageable);
}

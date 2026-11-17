package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.Producer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The producer catalogue's list (4A-1). */
public interface ProducerSearch {

    /** {@code query} matches the code or the name, case-insensitively; null filters mean "any". */
    Page<Producer> search(String query, Producer.Status status, Pageable pageable);
}

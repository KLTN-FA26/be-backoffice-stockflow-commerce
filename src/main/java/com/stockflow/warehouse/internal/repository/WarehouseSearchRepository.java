package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The warehouse list. Kept off the domain port because paging is Spring Data's vocabulary, not the
 * domain's ({@code AggregateRepository}).
 *
 * <p>Returns the aggregate rather than a projection: a warehouse has no child collections, so a
 * page of them is one query either way.</p>
 */
public interface WarehouseSearchRepository {

    /** @param term matched against prefix and name, case-insensitively; {@code null} for all */
    Page<Warehouse> search(String term, WarehouseStatus status, Pageable pageable);
}

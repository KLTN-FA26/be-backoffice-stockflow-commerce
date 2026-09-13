package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.api.PurchaseOrderSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The paginated, filterable PO list — deliberately <b>not</b> part of {@code
 * PurchaseOrderRepository}, the aggregate's domain port. Same reasoning as {@code
 * product.internal.repository.ProductSearchRepository}: {@code Page}/{@code Pageable} are banned
 * from {@code internal.domain} by {@code ArchitectureTest.domainDoesNotDependOnFrameworks}, so
 * listing lives here, implemented by the same adapter class.
 *
 * <p>Never fetch-joins {@code lines} for the same reason {@code ProductSearchRepository} never
 * fetch-joins images — combining it with {@code Pageable} triggers Hibernate's in-memory
 * pagination trap. Every {@link PurchaseOrderSummary} this returns has empty {@code lines}; only
 * {@code PurchaseOrderRepository.findById} (a single row) loads them.</p>
 */
public interface PurchaseOrderSearchRepository {

    Page<PurchaseOrderSummary> search(PurchaseOrderSearchCriteria criteria, Pageable pageable);
}

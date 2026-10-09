package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * The read side of the product master — deliberately <b>not</b> part of {@code ProductRepository},
 * the aggregate's domain port.
 *
 * <p>{@code AggregateRepository}'s own javadoc, and {@code ArchitectureTest.
 * domainDoesNotDependOnFrameworks}, ban {@code Page}/{@code Pageable}/{@code Specification} from
 * anything in {@code internal.domain}. A list screen genuinely needs all three, so this interface
 * lives here instead, next to the port rather than on it — implemented by the same adapter class,
 * reachable only by this module's own service.</p>
 *
 * <p>The summary carries the brand name and the primary category, which are not the aggregate's;
 * both come from scalar subqueries on the entity, so a page of products is one query.</p>
 */
public interface ProductSearchRepository {

    Page<ProductSummary> search(ProductSearchCriteria criteria, Pageable pageable);

    /** As stored now: called after a write, it reads what the write left, flushed and refreshed. */
    Optional<ProductSummary> summary(UUID productId);
}

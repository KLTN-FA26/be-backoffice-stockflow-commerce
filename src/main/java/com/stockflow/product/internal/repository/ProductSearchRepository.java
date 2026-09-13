package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The paginated, filterable product list — deliberately <b>not</b> part of {@code
 * ProductRepository}, the aggregate's domain port.
 *
 * <p>{@code AggregateRepository}'s own javadoc, and {@code ArchitectureTest.
 * domainDoesNotDependOnFrameworks}, ban {@code Page}/{@code Pageable}/{@code Specification} from
 * anything in {@code internal.domain}. A list screen genuinely needs all three, so this interface
 * lives here instead, next to the port rather than on it — implemented by the same adapter class,
 * reachable only by this module's own service (public because {@code internal.service} is a
 * different package, but never exported to {@code api}). This is the same shape as {@code
 * inventory}'s {@code StockConsumption}: a capability deliberately kept outside the module's main
 * port for a documented reason, still invisible to every other module because both interfaces live
 * under {@code internal}.</p>
 *
 * <p><b>Never fetch-joins the media gallery.</b> Combining a {@code LEFT JOIN FETCH} on a
 * {@code @OneToMany} with {@code Pageable} triggers Hibernate's "firstResult/maxResults specified
 * with collection fetch; applying in memory" trap — pagination would silently move from the
 * database into the JVM. Every {@link ProductSummary} this returns has an empty {@code images}
 * list; only {@code ProductRepository.findById} (a single row) loads them.</p>
 */
public interface ProductSearchRepository {

    Page<ProductSummary> search(ProductSearchCriteria criteria, Pageable pageable);
}

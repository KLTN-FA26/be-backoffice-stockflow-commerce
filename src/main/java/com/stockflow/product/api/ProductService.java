package com.stockflow.product.api;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the product module — the only package other modules may import.
 *
 * <p>Every parameter and return type is a record or enum declared in THIS package, never a
 * domain object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal} and
 * {@code theApiPublishesNoEntities} enforce it).</p>
 *
 * <p><b>Scope note (SCRUM-56/WBS 3.1.1.2):</b> master-data CRUD only. {@code submit}/{@code
 * approve}/{@code reject} land on the stacked SCRUM-57 branch (WBS 3.1.1.3) — every product created
 * through this interface today stays {@link ProductStatus#DRAFT}.</p>
 */
public interface ProductService {

    ProductSummary create(CreateProductCommand command);

    Optional<ProductSummary> findById(UUID productId);

    /** Paginated, filterable list. {@code images} is empty on every row — see {@link ProductSummary}. */
    PageResponse<ProductSummary> list(ListProductsQuery query);

    ProductSummary update(UpdateProductCommand command);
}

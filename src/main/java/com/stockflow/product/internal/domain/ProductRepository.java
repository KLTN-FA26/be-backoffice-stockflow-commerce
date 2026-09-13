package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The persistence <b>port</b> for {@link Product}.
 *
 * <p>Pure Java, no {@code Page}/{@code Specification}/JPA — {@code ArchitectureTest.
 * domainDoesNotDependOnFrameworks} enforces it. The paginated, filterable product list is a
 * separate, deliberately non-port interface: see {@code ProductSearchRepository} in
 * {@code internal.repository} for why.</p>
 */
public interface ProductRepository extends AggregateRepository<Product, ProductId> {

    Optional<Product> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * {@code Category} has no aggregate or port of its own yet (still a flat reference table) —
     * hosted here rather than invented elsewhere, since this is the only module that currently
     * needs to ask.
     */
    boolean categoryExists(UUID categoryId);
}

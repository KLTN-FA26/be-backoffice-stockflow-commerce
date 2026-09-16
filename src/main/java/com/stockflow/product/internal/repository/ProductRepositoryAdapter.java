package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.common.persistence.Specs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implements both the aggregate's domain port and the list-only search interface on top
 * of Spring Data. One class, two interfaces — see {@link ProductSearchRepository}'s javadoc for
 * why listing isn't part of {@link ProductRepository}.
 */
@Repository
class ProductRepositoryAdapter implements ProductRepository, ProductSearchRepository {

    private final ProductJpaRepository jpa;
    private final CategoryJpaRepository categories;

    ProductRepositoryAdapter(ProductJpaRepository jpa, CategoryJpaRepository categories) {
        this.jpa = jpa;
        this.categories = categories;
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jpa.findByIdWithImages(id.value()).map(ProductPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Product> findByCode(String code) {
        return jpa.findByCode(code).map(ProductPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpa.existsByCode(code);
    }

    @Override
    public boolean existsById(ProductId id) {
        return jpa.existsById(id.value());
    }

    @Override
    public boolean categoryExists(UUID categoryId) {
        return categories.existsById(categoryId);
    }

    /**
     * Insert or update.
     *
     * <p>The aggregate carries its own id from creation, so {@code save()} cannot tell new from
     * existing the way a generated id would — look the row up first and copy onto it, same shape
     * as {@code StockItemRepositoryAdapter.save}.</p>
     */
    @Override
    public Product save(Product product) {
        Optional<ProductJpaEntity> existing = jpa.findByIdWithImages(product.id().value());
        if (existing.isPresent()) {
            ProductJpaEntity managed = existing.get();
            ProductPersistenceMapper.applyToEntity(product, managed);
            // saveAndFlush, not save: @PreUpdate (which sets lastModifiedAt/lastModifiedBy) only
            // runs at flush time, so reading the entity back from a plain save() here would return
            // its stale pre-update audit fields even though the eventual UPDATE is correct.
            return ProductPersistenceMapper.toDomain(jpa.saveAndFlush(managed));
        }
        return ProductPersistenceMapper.toDomain(
                jpa.save(ProductPersistenceMapper.toNewEntity(product)));
    }

    @Override
    public Page<ProductSummary> search(ProductSearchCriteria criteria, Pageable pageable) {
        Specification<ProductJpaEntity> spec = Specification
                .<ProductJpaEntity>where(Specs.contains("name", criteria.search()))
                .or(Specs.contains("code", criteria.search()))
                .and(Specs.in("status", criteria.statuses()));
        return jpa.findAll(spec, pageable).map(ProductPersistenceMapper::toSummaryWithoutImages);
    }
}

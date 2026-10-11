package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.entity.ProductJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implements both the aggregate's domain port and the read-side search interface on top
 * of Spring Data. One class, two interfaces — see {@link ProductSearchRepository}'s javadoc for why
 * listing isn't part of {@link ProductRepository}.
 */
@Repository
class ProductRepositoryAdapter implements ProductRepository, ProductSearchRepository {

    private final ProductJpaRepository jpa;
    private final CategoryJpaRepository categories;
    private final BrandJpaRepository brands;
    private final VariantJpaRepository variants;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    ProductRepositoryAdapter(ProductJpaRepository jpa, CategoryJpaRepository categories, BrandJpaRepository brands,
                             VariantJpaRepository variants, JdbcTemplate jdbc, EntityManager entityManager) {
        this.jpa = jpa;
        this.categories = categories;
        this.brands = brands;
        this.variants = variants;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jpa.findById(id.value()).map(ProductPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Product> findForUpdate(ProductId id) {
        return jpa.lockById(id.value()).map(ProductPersistenceMapper::toDomain);
    }

    @Override
    public boolean containsSku(UUID productId, String sku) {
        return jpa.containsSku(productId, sku);
    }

    @Override
    public Optional<String> nameForSku(String sku) {
        var names = jpa.namesForSku(sku);
        return names.size() == 1 ? Optional.of(names.getFirst()) : Optional.empty();
    }

    @Override
    public boolean existsByCode(String code) {
        return jpa.existsByCode(code);
    }

    @Override
    public boolean skuExists(String sku) {
        return variants.existsBySku(sku);
    }

    @Override
    public boolean existsById(ProductId id) {
        return jpa.existsById(id.value());
    }

    @Override
    public boolean categoryExists(UUID categoryId) {
        return categories.existsById(categoryId);
    }

    @Override
    public boolean brandExists(UUID brandId) {
        return brands.existsById(brandId);
    }

    /**
     * Insert or update, then the primary category, then a refresh.
     *
     * <p>The flush comes first because the category row references the product. The refresh comes
     * last because the entity's brand name and primary category are subqueries and its audit
     * columns are set at flush time: without it, the summary read right after a write would show
     * what was there before it.</p>
     */
    @Override
    public Product save(Product product) {
        UUID id = product.id().value();
        ProductJpaEntity entity = jpa.findById(id)
                .orElseGet(() -> new ProductJpaEntity(id, product.code(), slugFor(product.code(), id)));
        ProductPersistenceMapper.applyToEntity(product, entity);
        entity = jpa.saveAndFlush(entity);
        writePrimaryCategory(id, product.categoryId());
        entityManager.refresh(entity);
        return ProductPersistenceMapper.toDomain(entity);
    }

    @Override
    public Page<ProductSummary> search(ProductSearchCriteria criteria, Pageable pageable) {
        Specification<ProductJpaEntity> spec = Specification
                .<ProductJpaEntity>where(Specs.<ProductJpaEntity>contains("name", criteria.search())
                        .or(Specs.contains("code", criteria.search())))
                .and(Specs.in("status", criteria.statuses()))
                .and(Specs.eq("categoryId", criteria.categoryId()))
                .and(Specs.eq("brandId", criteria.brandId()));
        return jpa.findAll(spec, pageable).map(ProductPersistenceMapper::toSummary);
    }

    @Override
    public Optional<ProductSummary> summary(UUID productId) {
        return jpa.findById(productId).map(ProductPersistenceMapper::toSummary);
    }

    /** The one primary category ({@code uk_product_categories_primary}); secondary ones are kept. */
    private void writePrimaryCategory(UUID productId, UUID categoryId) {
        jdbc.update("""
                delete from product.product_categories
                 where product_id = ? and is_primary and category_id is distinct from ?
                """, productId, categoryId);
        if (categoryId != null) {
            jdbc.update("""
                    insert into product.product_categories (id, product_id, category_id, is_primary, created_at)
                    values (?, ?, ?, true, now())
                    on conflict (product_id, category_id) do update set is_primary = true, last_modified_at = now()
                    """, com.stockflow.common.id.Identifiers.newId(), productId, categoryId);
        }
    }

    /** The storefront slug: the code in lower case, made unique with the id when another product has it. */
    private String slugFor(String code, UUID id) {
        String slug = Slugs.of(code);
        return jpa.existsBySlug(slug) ? slug + "-" + id.toString().replace("-", "").substring(24) : slug;
    }
}

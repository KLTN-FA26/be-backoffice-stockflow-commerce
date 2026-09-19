package com.stockflow.product.internal.repository;

import com.stockflow.product.internal.entity.ProductJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link ProductJpaEntity}. */
public interface ProductJpaRepository extends BaseJpaRepository<ProductJpaEntity> {

    @Query("select (count(s) > 0) from SkuJpaEntity s, VariantJpaEntity v "
            + "where s.variantId = v.id and v.productId = :productId and s.code = :sku")
    boolean containsSku(@Param("productId") UUID productId, @Param("sku") String sku);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> lockById(@Param("id") UUID id);

    /**
     * Load one product with its media gallery in a single query.
     *
     * <p>Only for a single-row read. Combining this fetch join with {@code Pageable} would make
     * Hibernate paginate in memory (the classic "firstResult/maxResults specified with collection
     * fetch" trap) — the list query in {@link ProductSearchRepository} never joins {@code images}.</p>
     */
    @Query("""
            select distinct p from ProductJpaEntity p
            left join fetch p.images
            where p.id = :id
            """)
    Optional<ProductJpaEntity> findByIdWithImages(@Param("id") UUID id);

    boolean existsByCode(String code);

    Optional<ProductJpaEntity> findByCode(String code);
}

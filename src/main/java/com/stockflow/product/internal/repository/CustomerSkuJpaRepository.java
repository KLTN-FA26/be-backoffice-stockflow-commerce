package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.domain.CustomerSkuStatus;
import com.stockflow.product.internal.entity.CustomerSkuJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code product.customer_skus}: a customer's own SKUs (V20270105000100). */
public interface CustomerSkuJpaRepository extends BaseJpaRepository<CustomerSkuJpaEntity> {

    Optional<CustomerSkuJpaEntity> findBySku(String sku);

    List<CustomerSkuJpaEntity> findBySkuIn(Collection<String> skus);

    Optional<CustomerSkuJpaEntity> findByDesignSnapshotId(UUID designSnapshotId);

    List<CustomerSkuJpaEntity> findByDesignSnapshotIdIn(Collection<UUID> designSnapshotIds);

    boolean existsBySku(String sku);

    boolean existsBySkuAndStatus(String sku, CustomerSkuStatus status);

    long countByProductIdAndCustomerId(UUID productId, UUID customerId);

    /** The product of a SKU, as a projection: what a lifecycle move locks before the SKU, loading no entity. */
    @Query("select c.productId from CustomerSkuJpaEntity c where c.id = :id")
    Optional<UUID> productIdOf(@Param("id") UUID id);

    /** The SKU, locked until the transaction ends: what a lifecycle move takes before it judges the status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerSkuJpaEntity c where c.id = :id")
    Optional<CustomerSkuJpaEntity> lockById(@Param("id") UUID id);

    /** The SKUs among {@code skus} (upper case) that belong to a customer other than {@code customerId}. */
    @Query("select c.sku from CustomerSkuJpaEntity c where upper(c.sku) in :skus and c.customerId <> :customerId")
    List<String> ownedByOthers(@Param("skus") Collection<String> skus, @Param("customerId") UUID customerId);

    /** A customer's own SKUs by product, then code; of one product when {@code productId} is set. */
    @Query("select c from CustomerSkuJpaEntity c where c.customerId = :customerId "
            + "and (:productId is null or c.productId = :productId) order by c.productId, c.sku, c.id")
    List<CustomerSkuJpaEntity> ownedBy(@Param("customerId") UUID customerId, @Param("productId") UUID productId);

    /** The customers' SKUs drawn on one variant, by code. */
    List<CustomerSkuJpaEntity> findByVariantIdOrderBySkuAscIdAsc(UUID variantId,
                                                                 org.springframework.data.domain.Pageable page);

    long countByVariantId(UUID variantId);

    /** Every customer's SKU of a product, by code: what discontinuing the product retires with its variants. */
    List<CustomerSkuJpaEntity> findByProductIdOrderBySkuAscIdAsc(UUID productId);

    List<CustomerSkuJpaEntity> findByProductIdAndStatus(UUID productId, CustomerSkuStatus status);

    /** {@code [sku, variant id]} of each SKU among {@code skus}: a projection, for inventory's carton lookups. */
    @Query("select c.sku, c.variantId from CustomerSkuJpaEntity c where c.sku in :skus")
    List<Object[]> variantIdsBySku(@Param("skus") Collection<String> skus);

    /** {@code [sku, name]} of each SKU among {@code skus}: a projection, so a label sheet loads no entity. */
    @Query("select c.sku, c.name from CustomerSkuJpaEntity c where c.sku in :skus")
    List<Object[]> namesBySku(@Param("skus") Collection<String> skus);

    /** {@code [sku, product name, SKU name]} of each SKU among {@code skus}, in one query. */
    @Query("""
            select c.sku, p.name, c.name from CustomerSkuJpaEntity c, ProductJpaEntity p
             where p.id = c.productId and c.sku in :skus""")
    List<Object[]> itemNamesBySku(@Param("skus") Collection<String> skus);
}

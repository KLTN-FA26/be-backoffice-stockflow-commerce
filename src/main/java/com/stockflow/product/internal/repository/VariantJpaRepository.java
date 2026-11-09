package com.stockflow.product.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.entity.VariantJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariantJpaRepository extends BaseJpaRepository<VariantJpaEntity> {

    Optional<VariantJpaEntity> findByIdAndProductId(UUID id, UUID productId);

    Optional<VariantJpaEntity> findByProductIdAndDefaultVariantTrue(UUID productId);

    Optional<VariantJpaEntity> findBySku(String sku);

    List<VariantJpaEntity> findBySkuIn(java.util.Collection<String> skus);

    Optional<VariantJpaEntity> findByDesignSnapshotId(UUID designSnapshotId);

    List<VariantJpaEntity> findByDesignSnapshotIdIn(java.util.Collection<UUID> designSnapshotIds);

    long countByProductIdAndOwnerCustomerId(UUID productId, UUID ownerCustomerId);

    /** The SKUs among {@code skus} that belong to a customer other than {@code customerId}. */
    @Query("select v.sku from VariantJpaEntity v where upper(v.sku) in :skus and v.ownerCustomerId is not null "
            + "and v.ownerCustomerId <> :customerId")
    List<String> ownedByOthers(@Param("skus") java.util.Collection<String> skus, @Param("customerId") UUID customerId);

    /** A customer's own SKUs by product, then position; of one product when {@code productId} is set. */
    @Query("select v from VariantJpaEntity v where v.ownerCustomerId = :customerId "
            + "and (:productId is null or v.productId = :productId) order by v.productId, v.position, v.id")
    List<VariantJpaEntity> ownedBy(@Param("customerId") UUID customerId, @Param("productId") UUID productId);

    List<VariantJpaEntity> findByProductIdAndStatus(UUID productId, VariantStatus status);

    List<VariantJpaEntity> findByProductIdOrderByPositionAscIdAsc(UUID productId);

    boolean existsBySku(String sku);

    boolean existsBySkuAndStatus(String sku, VariantStatus status);

    boolean existsByProductIdAndAttributeSignature(UUID productId, String attributeSignature);

    @Query("select coalesce(max(v.position), -1) + 1 from VariantJpaEntity v where v.productId = :productId")
    int nextPosition(@Param("productId") UUID productId);
}

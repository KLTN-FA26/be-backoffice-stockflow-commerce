package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Snapshot evidence. Legacy snapshots are not eligible for new workflow confirmation replay. */
public interface DesignSnapshotJpaRepository extends BaseJpaRepository<DesignSnapshotJpaEntity> {

    @Query("select s from DesignSnapshotJpaEntity s where s.draftId = :draftId and s.confirmedBy is not null and s.artifactId is not null")
    java.util.Optional<DesignSnapshotJpaEntity> findByDraftId(@Param("draftId") UUID draftId);

    boolean existsByDraftId(UUID draftId);

    /**
     * The customer-confirmed snapshots of one customer (the same eligibility {@code DesignService.verifySnapshot}
     * applies: confirmed by the owner, with a primary artifact), newest first.
     */
    @Query(value = "select s from DesignSnapshotJpaEntity s where s.confirmedBy is not null and s.artifactId is not null"
            + " and s.draftId in (select d.id from DesignDraftJpaEntity d where d.customerId = :customerId)"
            + " order by s.confirmedAt desc, s.id desc",
            countQuery = "select count(s) from DesignSnapshotJpaEntity s where s.confirmedBy is not null"
            + " and s.artifactId is not null"
            + " and s.draftId in (select d.id from DesignDraftJpaEntity d where d.customerId = :customerId)")
    Page<DesignSnapshotJpaEntity> findConfirmedOfCustomer(@Param("customerId") UUID customerId, Pageable pageable);

    /** The same, narrowed to designs of these products. */
    @Query(value = "select s from DesignSnapshotJpaEntity s where s.confirmedBy is not null and s.artifactId is not null"
            + " and s.draftId in (select d.id from DesignDraftJpaEntity d where d.customerId = :customerId"
            + " and d.productId in :productIds)"
            + " order by s.confirmedAt desc, s.id desc",
            countQuery = "select count(s) from DesignSnapshotJpaEntity s where s.confirmedBy is not null"
            + " and s.artifactId is not null"
            + " and s.draftId in (select d.id from DesignDraftJpaEntity d where d.customerId = :customerId"
            + " and d.productId in :productIds)")
    Page<DesignSnapshotJpaEntity> findConfirmedOfCustomer(@Param("customerId") UUID customerId,
                                                          @Param("productIds") Collection<UUID> productIds,
                                                          Pageable pageable);

    /** The products a customer has confirmed designs for: the candidates a SKU filter is checked against. */
    @Query("select distinct d.productId from DesignDraftJpaEntity d where d.customerId = :customerId and exists"
            + " (select 1 from DesignSnapshotJpaEntity s where s.draftId = d.id and s.confirmedBy is not null"
            + " and s.artifactId is not null)")
    List<UUID> confirmedProductsOfCustomer(@Param("customerId") UUID customerId);
}

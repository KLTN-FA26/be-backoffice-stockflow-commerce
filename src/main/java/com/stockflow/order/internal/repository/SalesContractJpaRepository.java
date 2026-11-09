package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.SalesContractJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SalesContractJpaRepository extends JpaRepository<SalesContractJpaEntity, UUID> {

    @Query("select c from SalesContractJpaEntity c where c.orderId = :orderId "
            + "and c.status <> com.stockflow.order.internal.domain.SalesContractStatus.CANCELLED")
    Optional<SalesContractJpaEntity> findLiveByOrderId(@Param("orderId") UUID orderId);

    List<SalesContractJpaEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    int countByOrderId(UUID orderId);

    @Query("select c.sentDocumentKey from SalesContractJpaEntity c where c.sentDocumentKey in :keys")
    List<String> sentDocumentKeysIn(@Param("keys") Collection<String> keys);

    @Query("select c.signedFileKey from SalesContractJpaEntity c where c.signedFileKey in :keys")
    List<String> signedFileKeysIn(@Param("keys") Collection<String> keys);
}

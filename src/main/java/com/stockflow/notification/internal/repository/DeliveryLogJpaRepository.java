package com.stockflow.notification.internal.repository;

import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link DeliveryLogJpaEntity}. STARTER STUB. */
public interface DeliveryLogJpaRepository extends BaseJpaRepository<DeliveryLogJpaEntity> {
    long countByOperationReferenceAndStatus(String reference, com.stockflow.notification.internal.domain.DeliveryStatus status);
    boolean existsByOperationReferenceAndTerminalTrue(String reference);
    long countByOperationReferenceAndDeliveryGenerationAndStatus(String reference, int generation, com.stockflow.notification.internal.domain.DeliveryStatus status);
    boolean existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(String reference, int generation);

    interface LatestStatus {
        String getReference();
        String getStatus();
        boolean getTerminal();
    }

    @org.springframework.data.jpa.repository.Query(value = """
            select 'purchase-order:' || c.purchase_order_id as reference,
                   case when exists (select 1 from notification.delivery_log s where s.external_reference='purchase-order:' || c.purchase_order_id)
                        then 'SENT' when c.suppressed then 'SUPPRESSED' else coalesce(l.status, 'QUEUED') end as status,
                   coalesce(l.terminal, false) as terminal
            from notification.po_delivery_control c
            left join lateral (
                select status,terminal from notification.delivery_log
                where operation_reference='purchase-order:' || c.purchase_order_id and delivery_generation=c.generation
                order by created_at desc,id desc limit 1
            ) l on true
            where 'purchase-order:' || c.purchase_order_id in (:references)
            """, nativeQuery = true)
    java.util.List<LatestStatus> latestStatuses(java.util.Collection<String> references);
    /** Transaction-scoped serialization also covers the first attempt, before a log row exists. */
    @org.springframework.data.jpa.repository.Query(value = "select pg_try_advisory_xact_lock(hashtextextended(:reference, 0))", nativeQuery = true)
    boolean tryLockDelivery(String reference);

    boolean existsByExternalReference(String externalReference);
    org.springframework.data.domain.Page<DeliveryLogJpaEntity> findByOperationReference(
            String reference, org.springframework.data.domain.Pageable pageable);
}

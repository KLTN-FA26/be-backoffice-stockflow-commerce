package com.stockflow.notification.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

/** Delivery evidence and bounded status projections shared by services inside notification. */
public interface DeliveryLogJpaRepository extends BaseJpaRepository<DeliveryLogJpaEntity> {
    long countByOperationReferenceAndDeliveryGeneration(String reference, int generation);

    long countByOperationReferenceAndStatus(String reference, DeliveryStatus status);

    boolean existsByOperationReferenceAndTerminalTrue(String reference);

    long countByOperationReferenceAndDeliveryGenerationAndStatus(
            String reference, int generation, DeliveryStatus status);

    boolean existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(
            String reference, int generation);

    interface LatestStatus {
        String getReference();

        String getStatus();

        boolean getTerminal();
    }

    @Query(
            value =
                    """
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
""",
            nativeQuery = true)
    List<LatestStatus> latestStatuses(Collection<String> references);

    @Query(
            value =
                    """
select 'purchase-order:' || c.purchase_order_id || ':cancellation' as reference,
    coalesce(l.status, 'QUEUED') as status, coalesce(l.terminal,false) as terminal
from notification.po_delivery_control c
left join lateral (
    select status,terminal from notification.delivery_log
    where operation_reference='purchase-order:' || c.purchase_order_id || ':cancellation'
    order by case when status='SENT' then 0 else 1 end, created_at desc,id desc limit 1
) l on true
where c.cancellation_requested and 'purchase-order:' || c.purchase_order_id || ':cancellation' in (:references)
""",
            nativeQuery = true)
    List<LatestStatus> cancellationStatuses(Collection<String> references);

    Page<DeliveryLogJpaEntity> findByOperationReferenceIn(
            Collection<String> references, Pageable pageable);

    /** Transaction-scoped serialization also covers the first attempt, before a log row exists. */
    @Query(
            value = "select pg_try_advisory_xact_lock(hashtextextended(:reference, 0))",
            nativeQuery = true)
    boolean tryLockDelivery(String reference);

    boolean existsByExternalReference(String externalReference);

    Page<DeliveryLogJpaEntity> findByOperationReference(String reference, Pageable pageable);
}

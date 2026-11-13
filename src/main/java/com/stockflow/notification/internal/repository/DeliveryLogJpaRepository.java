package com.stockflow.notification.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** Delivery evidence and bounded status projections shared by services inside notification. */
public interface DeliveryLogJpaRepository extends BaseJpaRepository<DeliveryLogJpaEntity> {
    long countByOperationReferenceAndDeliveryGeneration(String reference, int generation);

    long countByOperationReferenceAndStatus(String reference, DeliveryStatus status);

    boolean existsByOperationReferenceAndTerminalTrue(String reference);

    long countByOperationReferenceAndDeliveryGenerationAndStatus(
            String reference, int generation, DeliveryStatus status);

    boolean existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(
            String reference, int generation);

    Page<DeliveryLogJpaEntity> findByOperationReferenceIn(
            Collection<String> references, Pageable pageable);

    /** Transaction-scoped serialization also covers the first attempt, before a log row exists. */
    @Query(
            value = "select pg_try_advisory_xact_lock(hashtextextended(:reference, 0))",
            nativeQuery = true)
    boolean tryLockDelivery(String reference);

    boolean existsByExternalReference(String externalReference);

    Page<DeliveryLogJpaEntity> findByOperationReference(String reference, Pageable pageable);

    /**
     * When the next attempt of a delivery may run: the latest {@code next_attempt_at} of its failed attempts, null when
     * none waits. Each failure sets a later time than the one before, so the maximum is the latest failure's.
     */
    @Query("select max(l.nextAttemptAt) from DeliveryLogJpaEntity l where l.operationReference = :reference")
    Instant nextAttemptAt(@Param("reference") String reference);

    /** {@link #nextAttemptAt(String)} of one generation: an earlier generation's waits do not hold up a later one. */
    @Query("""
            select max(l.nextAttemptAt) from DeliveryLogJpaEntity l
            where l.operationReference = :reference and l.deliveryGeneration = :generation""")
    Instant nextAttemptAt(@Param("reference") String reference, @Param("generation") int generation);

    /** The newest generation of a message, null when it was never attempted. */
    @Query("select max(l.deliveryGeneration) from DeliveryLogJpaEntity l where l.operationReference = :reference")
    Integer latestGeneration(@Param("reference") String reference);

    /**
     * Pushes the retryable failures of a message to {@code until} at least ({@code CustomerEmailLog.lease}); only
     * those rows may wait ({@code ck_delivery_log_next_attempt}).
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeliveryLogJpaEntity l set l.nextAttemptAt = :until
            where l.operationReference = :reference
              and l.status = com.stockflow.notification.internal.domain.DeliveryStatus.FAILED and l.terminal = false
              and (l.nextAttemptAt is null or l.nextAttemptAt < :until)""")
    int leaseNextAttempt(@Param("reference") String reference, @Param("until") Instant until);

    /**
     * The sent message a delivery webhook reports on, locked: two reports on one message (delivered, then a complaint)
     * arrive together often enough, and each decides against what the other may just have written.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeliveryLogJpaEntity> findByProviderAndProviderMessageId(EmailProvider provider, String providerMessageId);
}

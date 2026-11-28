package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.ContractSignOtpJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractSignOtpJpaRepository extends BaseJpaRepository<ContractSignOtpJpaEntity> {

    /**
     * Serialises the codes asked for on one contract until the transaction ends, so two requests cannot both pass the
     * resend and hourly limits. Wrapped in a sub-select because {@code pg_advisory_xact_lock} returns {@code void}.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    Integer lockContract(@Param("key") String key);

    /** The code that can still be entered, its row locked: attempts are counted under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ContractSignOtpJpaEntity o where o.contractId = :contractId "
            + "and o.consumedAt is null and o.supersededAt is null")
    Optional<ContractSignOtpJpaEntity> findLiveForUpdate(@Param("contractId") UUID contractId);

    Optional<ContractSignOtpJpaEntity> findFirstByContractIdOrderByCreatedAtDesc(UUID contractId);

    /** The codes issued since {@code since}, oldest first. */
    List<ContractSignOtpJpaEntity> findByContractIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID contractId,
                                                                                         Instant since);
}
